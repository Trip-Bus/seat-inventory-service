package com.tripbus.seatinventory.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * US-031: publica en el stream de Redis los mensajes pendientes del {@link Outbox}, en orden de
 * creación, con el mismo sobre de siempre (eventId = id del outbox, los consumidores deduplican
 * por eventId). SELECT ... FOR UPDATE SKIP LOCKED: varias réplicas pueden correrlo sin
 * publicar dos veces el mismo mensaje. Si Redis falla, el lote se corta en el primer error (sin
 * adelantar eventos posteriores) y lo pendiente sale en la siguiente vuelta.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration retention;

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher, TransactionTemplate tx, Clock clock,
                       @Value("${tripbus.jobs.outbox-retention:7d}") Duration retention) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.retention = retention;
    }

    private record Pending(UUID id, String type, String payload, Instant creadoEn) {
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.outbox-relay-ms:500}", initialDelay = 1000)
    public void relay() {
        try {
            Integer publicados;
            do {
                publicados = tx.execute(s -> publishBatch());
            } while (publicados != null && publicados == BATCH);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar el outbox (se reintentará): {}", e.getMessage());
        }
    }

    /** Publica hasta BATCH pendientes; devuelve cuántos quedaron publicados. */
    int publishBatch() {
        List<Pending> pendientes = jdbc.query("""
                SELECT id, event_type, payload::text AS payload, creado_en FROM outbox
                WHERE publicado_en IS NULL
                ORDER BY creado_en
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getString("payload"), rs.getObject("creado_en", OffsetDateTime.class).toInstant()), BATCH);
        List<UUID> publicados = new ArrayList<>();
        for (Pending p : pendientes) {
            try {
                publisher.publish(p.id().toString(), p.type(), p.creadoEn(), p.payload());
            } catch (RuntimeException e) {
                log.warn("Redis no disponible; {} evento(s) del outbox quedan pendientes: {}",
                        pendientes.size() - publicados.size(), e.getMessage());
                break;
            }
            publicados.add(p.id());
        }
        if (!publicados.isEmpty()) {
            Timestamp ahora = Timestamp.from(clock.instant());
            jdbc.batchUpdate("UPDATE outbox SET publicado_en = ? WHERE id = ?", publicados, publicados.size(),
                    (ps, id) -> {
                        ps.setTimestamp(1, ahora);
                        ps.setObject(2, id);
                    });
        }
        return publicados.size();
    }

    /** Borra los mensajes publicados hace más de la retención (7 días por defecto). */
    @Scheduled(cron = "${tripbus.jobs.outbox-purge-cron:0 30 3 * * *}", zone = "America/Bogota")
    public void purge() {
        purgeOld();
    }

    int purgeOld() {
        Integer borrados = tx.execute(s -> jdbc.update("DELETE FROM outbox WHERE publicado_en < ?",
                Timestamp.from(clock.instant().minus(retention))));
        int total = borrados == null ? 0 : borrados;
        if (total > 0) {
            log.info("Outbox: {} mensaje(s) publicados purgados", total);
        }
        return total;
    }
}
