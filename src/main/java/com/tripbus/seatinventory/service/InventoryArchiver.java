package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.redis.LeaderLock;
import com.tripbus.seatinventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HT-08: archivo de viajes viejos. Un job nocturno (en un solo nodo, con el {@link LeaderLock}) mueve los viajes que
 * salieron hace más de tripbus.inventory.archive-after-days (90 por defecto) a las tablas *_archive, con el mismo
 * esquema: el viaje, sus asientos, ocupaciones, retenciones (y sus asientos), lista de espera y auditoría. Va por
 * lotes de {@value #LOTE} viajes, cada lote en su propia transacción corta (copiar y borrar, todo o nada). La
 * ocupación por viaje (US-191) sigue leyendo los viajes archivados.
 */
@Component
public class InventoryArchiver {

    private static final Logger log = LoggerFactory.getLogger(InventoryArchiver.class);
    static final int LOTE = 500;

    /** Copia del lote al archivo (SELECT *: las tablas *_archive tienen las mismas columnas en el mismo orden). */
    private static final List<String> COPIAR = List.of(
            "INSERT INTO itineraries_archive SELECT * FROM itineraries WHERE id IN (:ids)",
            "INSERT INTO holds_archive SELECT * FROM holds WHERE itinerary_id IN (:ids)",
            """
            INSERT INTO hold_seats_archive SELECT hs.* FROM hold_seats hs
            JOIN holds h ON h.id = hs.hold_id WHERE h.itinerary_id IN (:ids)
            """,
            "INSERT INTO seat_occupancies_archive SELECT * FROM seat_occupancies WHERE itinerary_id IN (:ids)",
            "INSERT INTO seat_states_archive SELECT * FROM seat_states WHERE itinerary_id IN (:ids)",
            "INSERT INTO waitlist_archive SELECT * FROM waitlist WHERE itinerary_id IN (:ids)",
            "INSERT INTO seat_audit_log_archive SELECT * FROM seat_audit_log WHERE itinerary_id IN (:ids)");

    /** Borrado del lote de las tablas vivas (hold_seats antes que holds por la clave foránea). */
    private static final List<String> BORRAR = List.of(
            "DELETE FROM hold_seats WHERE hold_id IN (SELECT id FROM holds WHERE itinerary_id IN (:ids))",
            "DELETE FROM holds WHERE itinerary_id IN (:ids)",
            "DELETE FROM seat_occupancies WHERE itinerary_id IN (:ids)",
            "DELETE FROM seat_states WHERE itinerary_id IN (:ids)",
            "DELETE FROM waitlist WHERE itinerary_id IN (:ids)",
            "DELETE FROM seat_audit_log WHERE itinerary_id IN (:ids)",
            "DELETE FROM itineraries WHERE id IN (:ids)");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LeaderLock leaderLock;
    private final InventoryProperties properties;
    private final Clock clock;

    public InventoryArchiver(JdbcTemplate jdbc, TransactionTemplate tx, LeaderLock leaderLock,
                             InventoryProperties properties, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.tx = tx;
        this.leaderLock = leaderLock;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${tripbus.jobs.archive-cron:0 15 4 * * *}", zone = "America/Bogota")
    public void archivar() {
        leaderLock.runIfLeader("seat-inventory-archive", Duration.ofMinutes(30), this::archivarViejos);
    }

    /** Archiva todos los viajes que ya cumplieron el plazo, por lotes. Devuelve cuántos archivó. */
    public int archivarViejos() {
        Instant limite = clock.instant().minus(Duration.ofDays(properties.archiveAfterDays()));
        int total = 0;
        int lote;
        do {
            Integer archivados = tx.execute(status -> archivarLote(limite));
            lote = archivados == null ? 0 : archivados;
            total += lote;
        } while (lote == LOTE);
        if (total > 0) {
            log.info("Archivo: {} viaje(s) que salieron antes de {} pasaron a las tablas *_archive", total, limite);
        }
        return total;
    }

    /** Un lote: copia al archivo y borra de las tablas vivas. Requiere transacción. */
    int archivarLote(Instant limite) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM itineraries WHERE fecha_salida < :limite
                ORDER BY fecha_salida LIMIT :lote FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("limite", Timestamp.from(limite)).addValue("lote", LOTE), UUID.class);
        if (ids.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource viajes = new MapSqlParameterSource("ids", ids);
        for (String sql : COPIAR) {
            jdbc.update(sql, viajes);
        }
        for (String sql : BORRAR) {
            jdbc.update(sql, viajes);
        }
        return ids.size();
    }
}
