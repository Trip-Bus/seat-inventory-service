package com.tripbus.seatinventory.service;

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
        jdbc.update("""
                INSERT INTO itineraries (id, capacidad, fecha_salida, cancelado)
                VALUES (?, ?, ?, FALSE)
                ON CONFLICT (id) DO NOTHING
                """, itineraryId, seatNumbers.size(), fechaSalida != null ? Timestamp.from(fechaSalida) : null);
        jdbc.batchUpdate("""
                INSERT INTO seat_states (id, itinerary_id, seat_number, estado, version, actualizado_en)
                VALUES (?, ?, ?, 'DISPONIBLE', 0, now())
                ON CONFLICT (itinerary_id, seat_number) DO NOTHING
                """, seatNumbers, 100, (ps, seat) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, itineraryId);
            ps.setString(3, seat);
        });
    }
}
