package com.tripbus.seatinventory;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.EventProperties;
import com.tripbus.seatinventory.common.events.OutboxRelay;
import com.tripbus.seatinventory.common.observability.TracePropagation;
import com.tripbus.seatinventory.service.DistributedSeatLock;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HT-10 contra PostgreSQL y Redis reales (Testcontainers), con tracing (muestreo del 100 %) y métricas encendidos:
 * el contexto de la traza viaja del cambio de estado al outbox y del outbox al stream, el consumidor lo retoma,
 * y las métricas propias salen en /actuator/prometheus. Aquí el consumidor del stream sí corre.
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=1.0",
        "tripbus.events.consumer-enabled=true", "tripbus.events.stream=tripbus:events:obs",
        "tripbus.jobs.outbox-relay-ms=3600000"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("it")
@Testcontainers
@DirtiesContext
class ObservabilityIT {

    private static final Duration ESPERA = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private SeatInventoryService inventory;
    @Autowired
    private InventoryInitializer initializer;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Tracer tracer;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private EventProperties eventProperties;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void laTrazaViajaDelCambioDeEstadoAlOutboxYAlStream() {
        UUID itineraryId = nuevoItinerario();
        Span span = tracer.nextSpan().name("prueba").start();
        HoldResponse hold;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            hold = inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null);
            // Lo que imprime cada línea de log (patrón de texto y ECS).
            assertThat(MDC.get("traceId")).isEqualTo(span.context().traceId());
            assertThat(MDC.get("spanId")).isEqualTo(span.context().spanId());
        } finally {
            span.end();
        }
        String traceId = span.context().traceId();
        String guardado = jdbc.queryForObject("select trace_parent from outbox where event_type = 'SeatHeld' "
                + "and payload->>'holdId' = ?", String.class, hold.holdId().toString());
        assertThat(guardado).startsWith("00-" + traceId + "-");

        relay.relay();
        List<MapRecord<String, Object, Object>> publicados = redisTemplate.opsForStream()
                .range(eventProperties.stream(), Range.unbounded());
        assertThat(publicados).anySatisfy(r -> {
            assertThat(r.getValue()).containsEntry("type", "SeatHeld");
            assertThat(String.valueOf(r.getValue().get(TracePropagation.CAMPO))).startsWith("00-" + traceId + "-");
        });
    }

    @Test
    void elConsumidorRetomaLaTrazaYProcesaElEvento() {
        UUID itineraryId = nuevoItinerario();
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("eventId", UUID.randomUUID().toString());
        campos.put("type", "ItineraryRescheduled");
        campos.put("source", "catalog-service");
        campos.put("occurredAt", Instant.now().toString());
        campos.put("payload", "{\"itineraryId\":\"" + itineraryId + "\",\"fechaSalida\":\"2027-01-10T08:00:00Z\"}");
        campos.put(TracePropagation.CAMPO, "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(eventProperties.stream()).ofMap(campos));

        await().atMost(ESPERA).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select fecha_salida from itineraries where id = ?", Timestamp.class, itineraryId).toInstant())
                .isEqualTo(Instant.parse("2027-01-10T08:00:00Z")));
    }

    @Test
    void lasMetricasPropiasSalenEnPrometheus() throws Exception {
        UUID itineraryId = nuevoItinerario();
        HoldResponse confirmada = inventory.holdSeats(itineraryId, List.of("2"), UUID.randomUUID(), null);
        inventory.confirmSale(confirmada.holdId(), UUID.randomUUID());
        HoldResponse liberada = inventory.holdSeats(itineraryId, List.of("3"), UUID.randomUUID(), null);
        inventory.releaseHold(liberada.holdId(), null, "PRUEBA");
        HoldResponse vencida = inventory.holdSeats(itineraryId, List.of("4"), UUID.randomUUID(), null);
        jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", vencida.holdId());
        inventory.expireHold(vencida.holdId());
        // Otro "pasajero" tiene el lock de Redis del asiento 5: no se obtiene a tiempo.
        redisTemplate.opsForValue().set(DistributedSeatLock.key(itineraryId, "5"), "otro", Duration.ofSeconds(10));
        List<String> cinco = List.of("5");
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, cinco, userId, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SEAT_LOCKED");

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .containsPattern("tripbus_retenciones_total\\{[^}]*resultado=\"creada\"[^}]*} [1-9]")
                .containsPattern("tripbus_retenciones_total\\{[^}]*resultado=\"confirmada\"[^}]*} [1-9]")
                .containsPattern("tripbus_retenciones_total\\{[^}]*resultado=\"liberada\"[^}]*} [1-9]")
                .containsPattern("tripbus_retenciones_total\\{[^}]*resultado=\"expirada\"[^}]*} [1-9]")
                .containsPattern("tripbus_lock_fallido_total\\{[^}]*} [1-9]")
                .contains("tripbus_doble_venta_bloqueada_total")
                .containsPattern("tripbus_retencion_segundos_count\\{[^}]*} [1-9]");
    }

    private UUID nuevoItinerario() {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList(), null);
        return itineraryId;
    }
}
