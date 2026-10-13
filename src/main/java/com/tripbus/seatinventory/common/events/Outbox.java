package com.tripbus.seatinventory.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * US-031: escribe los eventos de dominio en la tabla outbox dentro de la transacción del cambio
 * de estado (propagación MANDATORY: sin transacción es un error de programación). Si la
 * transacción se revierte, el evento también; {@link OutboxRelay} los publica después.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** Último creado_en asignado: estrictamente creciente para respetar el orden de creación. */
    private final AtomicReference<Instant> ultimo = new AtomicReference<>(Instant.EPOCH);

    public Outbox(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Devuelve el id del mensaje, que será el eventId del evento publicado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID add(String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox (id, event_type, payload, creado_en) VALUES (?, ?, ?::jsonb, ?)",
                id, eventType, toJson(payload), Timestamp.from(siguienteInstante()));
        return id;
    }

    /**
     * PostgreSQL guarda microsegundos: dos eventos del mismo instante empatarían en creado_en.
     * Se avanza 1 µs cuando el reloj no avanzó, así el orden de inserción es el de publicación.
     */
    Instant siguienteInstante() {
        Instant ahora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return ultimo.accumulateAndGet(ahora,
                (previo, actual) -> actual.isAfter(previo) ? actual : previo.plus(1, ChronoUnit.MICROS));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload de evento no serializable", e);
        }
    }
}
