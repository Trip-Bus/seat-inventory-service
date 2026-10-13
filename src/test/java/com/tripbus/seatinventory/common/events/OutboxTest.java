package com.tripbus.seatinventory.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OutboxTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T15:00:00.123456789Z");

    private final Outbox outbox = new Outbox(mock(JdbcTemplate.class), new ObjectMapper(),
            Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void creadoEnEsEstrictamenteCrecienteAunqueElRelojNoAvance() {
        Instant primero = outbox.siguienteInstante();
        Instant segundo = outbox.siguienteInstante();
        Instant tercero = outbox.siguienteInstante();

        assertThat(primero).isEqualTo(AHORA.truncatedTo(ChronoUnit.MICROS));
        assertThat(segundo).isEqualTo(primero.plus(1, ChronoUnit.MICROS));
        assertThat(tercero).isEqualTo(segundo.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void unPayloadNoSerializableEsUnErrorDeProgramacion() {
        assertThatThrownBy(() -> outbox.add("Prueba", Map.of("x", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
