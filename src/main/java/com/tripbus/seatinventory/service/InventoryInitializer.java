package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.domain.SeatSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Crea los registros de asientos de un itinerario (todos DISPONIBLE). Idempotente:
 * ON CONFLICT DO NOTHING sobre la restricción única (itinerary_id, seat_number).
 */
@Component
public class InventoryInitializer {

    private final JdbcTemplate jdbc;

    public InventoryInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initialize(UUID itineraryId, List<String> seatNumbers, Instant fechaSalida) {
        initialize(itineraryId, seatNumbers, fechaSalida, null);
    }

    /**
     * Si el itinerario ya existía (p. ej. inicializado de forma perezosa sin empresa ni fecha), completa
     * company_id y fecha_salida que falten, sin pisar los que ya tiene.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initialize(UUID itineraryId, List<String> seatNumbers, Instant fechaSalida, UUID companyId) {
        guardar(itineraryId, seatNumbers.stream().map(SeatSpec::sinCategoria).toList(), fechaSalida, companyId);
    }

    /**
     * US-141: con la categoría de cada asiento. A un asiento que ya existía (inicializado sin categoría) se le
     * pone la que llega; si no llega, conserva la suya. Los nuevos sin categoría quedan ESTANDAR.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initializeSeats(UUID itineraryId, List<SeatSpec> seats, Instant fechaSalida, UUID companyId) {
        guardar(itineraryId, seats, fechaSalida, companyId);
    }

    private void guardar(UUID itineraryId, List<SeatSpec> seats, Instant fechaSalida, UUID companyId) {
        List<SeatSpec> validos = seats.stream().filter(s -> !s.numero().isEmpty()).toList();
        jdbc.update("""
                INSERT INTO itineraries (id, capacidad, fecha_salida, cancelado, company_id)
                VALUES (?, ?, ?, FALSE, ?)
                ON CONFLICT (id) DO UPDATE SET
                    company_id = COALESCE(itineraries.company_id, EXCLUDED.company_id),
                    fecha_salida = COALESCE(itineraries.fecha_salida, EXCLUDED.fecha_salida)
                """, itineraryId, validos.size(), fechaSalida != null ? Timestamp.from(fechaSalida) : null,
                companyId);
        jdbc.batchUpdate("""
                INSERT INTO seat_states (id, itinerary_id, seat_number, estado, version, actualizado_en, categoria)
                VALUES (?, ?, ?, 'DISPONIBLE', 0, now(), COALESCE(?, 'ESTANDAR'))
                ON CONFLICT (itinerary_id, seat_number) DO UPDATE
                    SET categoria = COALESCE(EXCLUDED.categoria, seat_states.categoria)
                    WHERE ? IS NOT NULL
                """, validos, 100, (ps, seat) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, itineraryId);
            ps.setString(3, seat.numero());
            ps.setString(4, seat.categoria());
            ps.setString(5, seat.categoria());
        });
    }
}
