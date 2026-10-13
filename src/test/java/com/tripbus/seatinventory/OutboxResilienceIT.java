package com.tripbus.seatinventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.tripbus.seatinventory.common.events.EventProperties;
import com.tripbus.seatinventory.common.events.OutboxRelay;
import com.tripbus.seatinventory.common.security.InternalTokenFilter;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-031 / US-022 / US-018: el inventario sigue funcionando y no pierde eventos cuando Redis cae.
 * Redis se DETIENE de verdad (stop del contenedor de Testcontainers de esta clase, nunca otros) y
 * se vuelve a iniciar en el mismo puerto del host, así el cliente de Spring se reconecta solo.
 * Todo lo que depende del relay o del barrido programado se espera con Awaitility.
 */
@SpringBootTest(properties = {
        "tripbus.jobs.outbox-relay-ms=200",
        "tripbus.jobs.sweep-interval-ms=500",
        // Un bloqueo de fila que no llega en 1 s falla con lock_not_available (prueba del 409).
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '1s'"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
@DirtiesContext
class OutboxResilienceIT {

    private static final Duration ESPERA = Duration.ofSeconds(30);
    private static final int REDIS_PORT = puertoLibre();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Puerto del host fijo: al reiniciar el contenedor detenido, Redis vuelve en la misma dirección.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_PORT);
    }

    @Autowired
    private SeatInventoryService inventory;
    @Autowired
    private InventoryInitializer initializer;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private EventProperties eventProperties;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    @Test
    void conRedisDetenidoLaVentaSeConfirmaYElEventoSalePorElOutboxAlVolver() {
        UUID itineraryId = nuevoItinerario();
        UUID bookingId = UUID.randomUUID();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null);
        await().atMost(ESPERA).until(() -> pendientes(itineraryId) == 0);

        UUID soldId;
        detenerRedis();
        try {
            inventory.confirmSale(hold.holdId(), bookingId);
            assertThat(estadoAsiento(itineraryId, "1")).isEqualTo("VENDIDO");
            soldId = jdbc.queryForObject(
                    "select id from outbox where event_type = 'SeatSold' and payload->>'holdId' = ?", UUID.class,
                    hold.holdId().toString());
            // Sin Redis el relay no puede publicarlo: sigue pendiente mientras Redis esté caído.
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                    .until(() -> !publicado(soldId));
        } finally {
            reanudarRedis();
        }

        await().atMost(ESPERA).untilAsserted(() -> {
            assertThat(publicado(soldId)).isTrue();
            JsonNode evento = eventosDelStream().stream()
                    .filter(r -> soldId.toString().equals(r.getValue().get("eventId")))
                    .map(r -> json(r.getValue().get("payload")))
                    .findFirst().orElseThrow();
            assertThat(evento.get("bookingId").asText()).isEqualTo(bookingId.toString());
            assertThat(evento.get("holdId").asText()).isEqualTo(hold.holdId().toString());
        });
    }

    @Test
    void losEventosAcumuladosSinRedisSePublicanEnOrdenDeCreacion() {
        UUID itineraryId = nuevoItinerario();
        HoldResponse compra = inventory.holdSeats(itineraryId, List.of("2", "3"), UUID.randomUUID(), null);
        HoldResponse abandonada = inventory.holdSeats(itineraryId, List.of("4"), UUID.randomUUID(), null);
        await().atMost(ESPERA).until(() -> pendientes(itineraryId) == 0);
        Instant corte = jdbc.queryForObject("select max(creado_en) from outbox where payload->>'itineraryId' = ?",
                OffsetDateTime.class, itineraryId.toString()).toInstant();

        detenerRedis();
        try {
            inventory.confirmSale(compra.holdId(), UUID.randomUUID());
            inventory.releaseHold(abandonada.holdId(), null, "PRUEBA");
        } finally {
            reanudarRedis();
        }

        List<UUID> enOrden = jdbc.queryForList(
                "select id from outbox where payload->>'itineraryId' = ? and creado_en > ? order by creado_en",
                UUID.class, itineraryId.toString(), Timestamp.from(corte));
        Set<String> ids = Set.copyOf(enOrden.stream().map(UUID::toString).toList());
        await().atMost(ESPERA).untilAsserted(() -> {
            assertThat(pendientes(itineraryId)).isZero();
            List<MapRecord<String, Object, Object>> publicados = eventosDelStream().stream()
                    .filter(r -> ids.contains((String) r.getValue().get("eventId"))).toList();
            assertThat(publicados).extracting(r -> UUID.fromString((String) r.getValue().get("eventId")))
                    .containsExactlyElementsOf(enOrden);
            // Mismo sobre de siempre (type, eventId, occurredAt, payload); occurredAt = creado_en del outbox.
            assertThat(publicados).extracting(r -> r.getValue().get("type")).containsExactly(
                    "SeatStateChanged", "SeatStateChanged", "SeatSold", "SeatStateChanged", "SeatReleased");
            assertThat(publicados).allSatisfy(r -> {
                assertThat(Instant.parse((String) r.getValue().get("occurredAt"))).isAfter(corte);
                assertThat(r.getValue().get("source")).isEqualTo("seat-inventory-service");
                assertThat(json(r.getValue().get("payload")).get("itineraryId").asText())
                        .isEqualTo(itineraryId.toString());
            });
        });
    }

    @Test
    void conRedisDetenidoElBarridoSigueLiberandoLasRetencionesVencidas() {
        UUID itineraryId = nuevoItinerario();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("5"), UUID.randomUUID(), null);

        detenerRedis();
        try {
            jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", hold.holdId());
            await().atMost(ESPERA).untilAsserted(() -> {
                assertThat(estadoAsiento(itineraryId, "5")).isEqualTo("DISPONIBLE");
                assertThat(jdbc.queryForObject("select estado from holds where id = ?", String.class,
                        hold.holdId())).isEqualTo("EXPIRADA");
            });
            // HoldExpired quedó en el outbox aunque Redis siga caído.
            assertThat(jdbc.queryForObject(
                    "select count(*) from outbox where event_type = 'HoldExpired' and payload->>'holdId' = ?",
                    Long.class, hold.holdId().toString())).isEqualTo(1L);
        } finally {
            reanudarRedis();
        }
    }

    @Test
    void unTimeoutDeBloqueoResponde409SeatLocked() throws Exception {
        UUID itineraryId = nuevoItinerario();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("6"), UUID.randomUUID(), null);

        // Otra transacción tiene la fila de la retención bloqueada más allá del lock_timeout.
        try (Connection otra = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            otra.setAutoCommit(false);
            try (PreparedStatement ps = otra.prepareStatement("select id from holds where id = ? for update")) {
                ps.setObject(1, hold.holdId());
                ps.executeQuery();
            }
            mockMvc.perform(post("/internal/holds/{holdId}/confirm", hold.holdId())
                            .header(InternalTokenFilter.HEADER, internalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bookingId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SEAT_LOCKED"));
            otra.rollback();
        }
        assertThat(estadoAsiento(itineraryId, "6")).isEqualTo("RETENIDO");
    }

    @Test
    void laPurgaDiariaBorraSoloLosPublicadosHaceMasDeSieteDias() {
        UUID viejo = insertarOutbox("now() - interval '10 days'", "now() - interval '8 days'");
        UUID reciente = insertarOutbox("now() - interval '2 days'", "now() - interval '1 day'");
        UUID pendiente = insertarOutbox("now() - interval '10 days'", null);

        relay.purge();

        assertThat(existe(viejo)).isFalse();
        assertThat(existe(reciente)).isTrue();
        assertThat(existe(pendiente)).isTrue();
    }

    // ------------------------------------------------------------------ utilidades

    private UUID nuevoItinerario() {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList(), null);
        return itineraryId;
    }

    private void detenerRedis() {
        redis.getDockerClient().stopContainerCmd(redis.getContainerId()).withTimeout(1).exec();
    }

    private void reanudarRedis() {
        redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
        await().atMost(ESPERA).ignoreExceptions()
                .until(() -> "PONG".equals(redisTemplate.execute(c -> c.ping(), true)));
    }

    private long pendientes(UUID itineraryId) {
        return jdbc.queryForObject(
                "select count(*) from outbox where publicado_en is null and payload->>'itineraryId' = ?", Long.class,
                itineraryId.toString());
    }

    private boolean publicado(UUID outboxId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select publicado_en is not null from outbox where id = ?",
                Boolean.class, outboxId));
    }

    private boolean existe(UUID outboxId) {
        return jdbc.queryForObject("select count(*) from outbox where id = ?", Long.class, outboxId) == 1L;
    }

    private UUID insertarOutbox(String creadoEn, String publicadoEn) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into outbox (id, event_type, payload, creado_en, publicado_en) values (?, 'Prueba', "
                + "'{}'::jsonb, " + creadoEn + ", " + publicadoEn + ")", id);
        return id;
    }

    private String estadoAsiento(UUID itineraryId, String seat) {
        return jdbc.queryForObject("select estado from seat_states where itinerary_id = ? and seat_number = ?",
                String.class, itineraryId, seat);
    }

    private List<MapRecord<String, Object, Object>> eventosDelStream() {
        return redisTemplate.opsForStream().range(eventProperties.stream(), Range.unbounded());
    }

    private JsonNode json(Object value) {
        try {
            return objectMapper.readTree((String) value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int puertoLibre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
