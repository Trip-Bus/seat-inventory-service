package com.tripbus.seatinventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.common.security.InternalTokenFilter;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.events.InventoryEventsHandler;
import com.tripbus.seatinventory.service.Actor;
import com.tripbus.seatinventory.service.ExpiredHoldsSweeper;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
import com.tripbus.seatinventory.service.WaitlistProcessor;
import com.tripbus.seatinventory.service.WaitlistScheduler;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 4: ocupación por viaje (US-191) y lista de espera (US-140), contra PostgreSQL 16 y Redis 7 reales
 * (Testcontainers) con las migraciones Flyway reales (V5: waitlist, índices y motivo_cierre).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
@DirtiesContext
class OccupancyWaitlistIT {

    private static final Duration ESPERA = Duration.ofSeconds(15);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private SeatInventoryService inventory;
    @Autowired
    private SeatLifecycleService lifecycle;
    @Autowired
    private InventoryInitializer initializer;
    @Autowired
    private InventoryEventsHandler eventsHandler;
    @Autowired
    private ExpiredHoldsSweeper sweeper;
    @Autowired
    private WaitlistProcessor processor;
    @Autowired
    private WaitlistScheduler scheduler;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    // ------------------------------------------------------------------ US-191 ocupación

    @Test
    void ocupacionConTodosLosEstadosYLaTasaDeAbandono() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(10, companyId, null);
        poblarTodosLosEstados(itineraryId);

        for (RequestPostProcessor quien : List.of(usuario("OPERADOR", companyId), usuario("ADMIN", null))) {
            mockMvc.perform(get("/seats/itineraries/{id}/occupancy", itineraryId).with(quien))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itineraryId").value(itineraryId.toString()))
                    .andExpect(jsonPath("$.capacidad").value(10))
                    .andExpect(jsonPath("$.disponibles").value(5))
                    .andExpect(jsonPath("$.retenidos").value(1))
                    .andExpect(jsonPath("$.vendidos").value(2))
                    .andExpect(jsonPath("$.enOferta").value(1))
                    .andExpect(jsonPath("$.bloqueados").value(1))
                    .andExpect(jsonPath("$.porcentajeOcupacion").value(20.0))
                    .andExpect(jsonPath("$.retenciones.creadas").value(5))
                    .andExpect(jsonPath("$.retenciones.activas").value(1))
                    .andExpect(jsonPath("$.retenciones.confirmadas").value(1))
                    .andExpect(jsonPath("$.retenciones.expiradas").value(1))
                    .andExpect(jsonPath("$.retenciones.liberadas").value(2))
                    .andExpect(jsonPath("$.retenciones.liberadasPorUsuario").value(1))
                    .andExpect(jsonPath("$.retenciones.canceladas").value(0))
                    // (1 expirada + 1 liberada por el usuario) / 5 creadas; la del pago rechazado no cuenta.
                    .andExpect(jsonPath("$.tasaAbandono").value(0.4));
        }
        assertThat(jdbc.queryForList("select motivo_cierre from holds where itinerary_id = ? "
                + "and motivo_cierre is not null", String.class, itineraryId))
                .containsExactlyInAnyOrder("EXPIRADA", "LIBERADA_POR_USUARIO", "PAGO_RECHAZADO");
    }

    @Test
    void laOcupacionDeOtraEmpresaNoExiste() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(4, companyId, null);

        for (RequestPostProcessor quien : List.of(usuario("OPERADOR", UUID.randomUUID()), usuario("OPERADOR", null),
                usuario("PASAJERO", companyId))) {
            mockMvc.perform(get("/seats/itineraries/{id}/occupancy", itineraryId).with(quien))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        }
        mockMvc.perform(get("/seats/itineraries/{id}/occupancy", UUID.randomUUID()).with(usuario("ADMIN", null)))
                .andExpect(status().isNotFound());
        // A diferencia del mapa de asientos, la ocupación no es pública.
        mockMvc.perform(get("/seats/itineraries/{id}/occupancy", itineraryId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId)).andExpect(status().isOk());
    }

    @Test
    void elInternoDevuelveVariosViajes() throws Exception {
        UUID uno = nuevoItinerario(10, null, null);
        poblarTodosLosEstados(uno);
        UUID cancelado = nuevoItinerario(4, null, null);
        inventory.holdSeats(cancelado, List.of("1", "2"), UUID.randomUUID(), null);
        vender(cancelado, "3");
        manejar("ItineraryCancelled", Map.of("itineraryId", cancelado, "motivo", "VIA_CERRADA"));
        UUID desconocido = UUID.randomUUID();

        mockMvc.perform(get("/internal/occupancy").header(InternalTokenFilter.HEADER, internalToken)
                        .param("itineraryIds", uno + "," + desconocido + "," + cancelado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itineraryId").value(uno.toString()))
                .andExpect(jsonPath("$[0].vendidos").value(2))
                .andExpect(jsonPath("$[0].tasaAbandono").value(0.4))
                .andExpect(jsonPath("$[1].itineraryId").value(cancelado.toString()))
                .andExpect(jsonPath("$[1].disponibles").value(3))
                .andExpect(jsonPath("$[1].vendidos").value(1))
                .andExpect(jsonPath("$[1].porcentajeOcupacion").value(25.0))
                .andExpect(jsonPath("$[1].retenciones.creadas").value(2))
                .andExpect(jsonPath("$[1].retenciones.canceladas").value(1))
                // Lo que liberó el sistema por la cancelación no es abandono.
                .andExpect(jsonPath("$[1].tasaAbandono").value(0.0));

        String demasiados = IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));
        mockMvc.perform(get("/internal/occupancy").header(InternalTokenFilter.HEADER, internalToken)
                        .param("itineraryIds", demasiados))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_ITINERARIES"));
        mockMvc.perform(get("/internal/occupancy").param("itineraryIds", uno.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ US-140 lista de espera

    @Test
    void anotarseConCupoDisponibleResponde409YLaFilaGuardaElOrden() throws Exception {
        UUID itineraryId = nuevoItinerario(3, null, Instant.now().plus(Duration.ofDays(2)));
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();

        anotarse(itineraryId, ana, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_AVAILABLE"))
                .andExpect(jsonPath("$.error.details.disponibles").value(3));
        vender(itineraryId, "1", "2");
        anotarse(itineraryId, ana, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_AVAILABLE"));

        anotarse(itineraryId, ana, 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ESPERANDO"))
                .andExpect(jsonPath("$.cantidad").value(2))
                .andExpect(jsonPath("$.posicion").value(1));
        anotarse(itineraryId, ana, 3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_IN_WAITLIST"));
        anotarse(itineraryId, beto, 7).andExpect(status().isBadRequest());
        anotarse(itineraryId, beto, 3).andExpect(status().isCreated())
                .andExpect(jsonPath("$.posicion").value(2));

        mockMvc.perform(get("/seats/waitlist/me").with(pasajero(beto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itineraryId").value(itineraryId.toString()))
                .andExpect(jsonPath("$[0].posicion").value(2));

        mockMvc.perform(delete("/seats/itineraries/{id}/waitlist", itineraryId).with(pasajero(ana)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/seats/waitlist/me").with(pasajero(beto)))
                .andExpect(jsonPath("$[0].posicion").value(1));
        mockMvc.perform(get("/seats/waitlist/me").with(pasajero(ana)))
                .andExpect(jsonPath("$[0].estado").value("CANCELADO"))
                .andExpect(jsonPath("$[0].posicion").doesNotExist());
        mockMvc.perform(delete("/seats/itineraries/{id}/waitlist", itineraryId).with(pasajero(ana)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WAITLIST_NOT_FOUND"));
        // Después de salir puede volver a anotarse (al final de la fila).
        anotarse(itineraryId, ana, 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.posicion").value(2));

        // A 30 minutos o menos de la salida, o con el viaje cancelado, no se puede.
        UUID pronto = nuevoItinerario(1, null, Instant.now().plus(Duration.ofMinutes(20)));
        vender(pronto, "1");
        anotarse(pronto, ana, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WAITLIST_CLOSED"));
        UUID cancelado = nuevoItinerario(1, null, null);
        manejar("ItineraryCancelled", Map.of("itineraryId", cancelado, "motivo", "X"));
        anotarse(cancelado, ana, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_CANCELLED"));
        // Sin sesión no hay lista de espera.
        mockMvc.perform(get("/seats/waitlist/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void laLiberacionOfreceAlPrimeroCuyaCantidadCabe() throws Exception {
        UUID itineraryId = nuevoItinerario(4, null, null);
        List<HoldResponse> holds = IntStream.rangeClosed(1, 4)
                .mapToObj(i -> inventory.holdSeats(itineraryId, List.of(String.valueOf(i)), UUID.randomUUID(), null))
                .toList();
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        anotarse(itineraryId, ana, 2).andExpect(status().isCreated());
        anotarse(itineraryId, beto, 1).andExpect(status().isCreated());

        // Se libera 1 asiento: a Ana (2) no le alcanza, así que se le ofrece a Beto, que sigue en la fila.
        liberar(holds.get(2));
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(itineraryId, beto).get("estado")));
        UUID holdBeto = (UUID) turno(itineraryId, beto).get("hold_id");
        assertThat(fila(itineraryId, "3")).containsEntry("estado", "RETENIDO").containsEntry("user_id", beto)
                .containsEntry("hold_id", holdBeto);
        assertThat(turno(itineraryId, ana)).containsEntry("estado", "ESPERANDO");

        // Con 2 libres se le ofrecen a Ana los primeros asientos libres.
        liberar(holds.get(0));
        liberar(holds.get(1));
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(itineraryId, ana).get("estado")));
        UUID holdAna = (UUID) turno(itineraryId, ana).get("hold_id");
        assertThat(eventos("WaitlistSeatOffered", itineraryId)).anySatisfy(p -> {
            assertThat(p.path("userId").asText()).isEqualTo(ana.toString());
            assertThat(p.path("holdId").asText()).isEqualTo(holdAna.toString());
            assertThat(p.path("seatNumbers").toString()).isEqualTo("[\"1\",\"2\"]");
            assertThat(p.path("expiraEn").isMissingNode()).isFalse();
        });
        mockMvc.perform(get("/seats/waitlist/me").with(pasajero(ana)))
                .andExpect(jsonPath("$[0].estado").value("OFRECIDO"))
                .andExpect(jsonPath("$[0].holdId").value(holdAna.toString()));

        // Beto sale de la fila con la oferta en la mano: su retención se libera como suya.
        mockMvc.perform(delete("/seats/itineraries/{id}/waitlist", itineraryId).with(pasajero(beto)))
                .andExpect(status().isNoContent());
        assertThat(turno(itineraryId, beto)).containsEntry("estado", "CANCELADO");
        assertThat(jdbc.queryForMap("select estado, motivo_cierre from holds where id = ?", holdBeto))
                .containsEntry("estado", "LIBERADA").containsEntry("motivo_cierre", Hold.LIBERADA_POR_USUARIO);
    }

    @Test
    void elVencimientoPasaAlSiguienteYLaVentaLoConvierte() throws Exception {
        UUID itineraryId = nuevoItinerario(2, null, null);
        HoldResponse primero = inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null);
        vender(itineraryId, "2");
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        anotarse(itineraryId, ana, 1).andExpect(status().isCreated());
        anotarse(itineraryId, beto, 1).andExpect(status().isCreated());

        liberar(primero);
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(itineraryId, ana).get("estado")));
        UUID holdAna = (UUID) turno(itineraryId, ana).get("hold_id");

        // Ana no paga: su retención vence, su turno queda VENCIDO y el asiento pasa a Beto.
        jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", holdAna);
        assertThat(sweeper.releaseExpiredHolds()).isPositive();
        assertThat(turno(itineraryId, ana)).containsEntry("estado", "VENCIDO");
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(itineraryId, beto).get("estado")));
        UUID holdBeto = (UUID) turno(itineraryId, beto).get("hold_id");
        assertThat(fila(itineraryId, "1")).containsEntry("user_id", beto).containsEntry("hold_id", holdBeto);

        // Beto paga: CONVERTIDO.
        inventory.confirmSale(holdBeto, UUID.randomUUID());
        assertThat(turno(itineraryId, beto)).containsEntry("estado", "CONVERTIDO");
        assertThat(eventos("WaitlistSeatOffered", itineraryId)).hasSize(2);
    }

    @Test
    void desbloquearUnAsientoTambienOfreceALaFila() throws Exception {
        UUID itineraryId = nuevoItinerario(2, null, null);
        Actor admin = new Actor(UUID.randomUUID(), Actor.ADMIN, null);
        lifecycle.blockSeats(itineraryId, List.of("2"), "Asiento roto", admin);
        vender(itineraryId, "1");
        UUID ana = UUID.randomUUID();
        anotarse(itineraryId, ana, 1).andExpect(status().isCreated());

        lifecycle.unblockSeats(itineraryId, List.of("2"), admin);
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(itineraryId, ana).get("estado")));
        assertThat(fila(itineraryId, "2")).containsEntry("estado", "RETENIDO").containsEntry("user_id", ana);
    }

    @Test
    void laFilaSeCierraAntesDeLaSalidaYAlCancelarseElViaje() throws Exception {
        UUID itineraryId = nuevoItinerario(1, null, Instant.now().plus(Duration.ofHours(3)));
        vender(itineraryId, "1");
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        anotarse(itineraryId, ana, 1).andExpect(status().isCreated());
        anotarse(itineraryId, beto, 1).andExpect(status().isCreated());

        jdbc.update("update itineraries set fecha_salida = ? where id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(25))), itineraryId);
        scheduler.revisarFilas();
        assertThat(turno(itineraryId, ana)).containsEntry("estado", "CANCELADO");
        assertThat(turno(itineraryId, beto)).containsEntry("estado", "CANCELADO");
        assertThat(eventos("WaitlistClosed", itineraryId)).singleElement().satisfies(p ->
                assertThat(p.path("userIds").toString()).contains(ana.toString(), beto.toString()));

        // Cancelación: se cierra toda la fila, también quien ya tenía una oferta (su retención se cancela).
        UUID otro = nuevoItinerario(2, null, null);
        HoldResponse ocupado = inventory.holdSeats(otro, List.of("1"), UUID.randomUUID(), null);
        vender(otro, "2");
        anotarse(otro, ana, 1).andExpect(status().isCreated());
        anotarse(otro, beto, 1).andExpect(status().isCreated());
        liberar(ocupado);
        await().atMost(ESPERA).until(() -> "OFRECIDO".equals(turno(otro, ana).get("estado")));
        UUID holdAna = (UUID) turno(otro, ana).get("hold_id");

        manejar("ItineraryCancelled", Map.of("itineraryId", otro, "motivo", "VIA_CERRADA"));
        assertThat(turno(otro, ana)).containsEntry("estado", "CANCELADO");
        assertThat(turno(otro, beto)).containsEntry("estado", "CANCELADO");
        assertThat(jdbc.queryForObject("select estado from holds where id = ?", String.class, holdAna))
                .isEqualTo("CANCELADA");
        assertThat(eventos("WaitlistClosed", otro)).singleElement().satisfies(p ->
                assertThat(p.path("userIds").toString()).contains(ana.toString(), beto.toString()));
        // Volver a procesar una fila ya cerrada no ofrece nada ni repite el aviso.
        assertThat(processor.procesar(otro)).isZero();
        assertThat(eventos("WaitlistClosed", otro)).hasSize(1);
    }

    @Test
    void veinteLiberacionesSimultaneasNoOfrecenElMismoAsientoADosPersonas() throws Exception {
        int asientos = 20;
        UUID itineraryId = nuevoItinerario(asientos, null, null);
        List<HoldResponse> holds = IntStream.rangeClosed(1, asientos)
                .mapToObj(i -> inventory.holdSeats(itineraryId, List.of(String.valueOf(i)), UUID.randomUUID(), null))
                .toList();
        Map<UUID, Integer> fila = Map.of(UUID.randomUUID(), 2, UUID.randomUUID(), 3, UUID.randomUUID(), 1);
        for (Map.Entry<UUID, Integer> e : fila.entrySet()) {
            anotarse(itineraryId, e.getKey(), e.getValue()).andExpect(status().isCreated());
        }

        ExecutorService pool = Executors.newFixedThreadPool(asientos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        try {
            for (HoldResponse hold : holds) {
                tareas.add(pool.submit(() -> {
                    salida.await();
                    liberar(hold);
                    // Además del procesamiento tras el commit, cada hilo procesa la fila él mismo.
                    processor.procesar(itineraryId);
                    return null;
                }));
            }
            salida.countDown();
            for (Future<?> tarea : tareas) {
                tarea.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(ESPERA).until(() -> jdbc.queryForObject("select count(*) from waitlist where itinerary_id = ? "
                + "and estado = 'OFRECIDO'", Long.class, itineraryId) == 3);
        Set<String> ofrecidos = new HashSet<>();
        for (Map.Entry<UUID, Integer> e : fila.entrySet()) {
            UUID holdId = (UUID) turno(itineraryId, e.getKey()).get("hold_id");
            List<String> suyos = jdbc.queryForList("select seat_number from seat_states where hold_id = ? "
                    + "and estado = 'RETENIDO' and user_id = ?", String.class, holdId, e.getKey());
            assertThat(suyos).hasSize(e.getValue());
            ofrecidos.addAll(suyos);
        }
        // Nadie recibió el mismo asiento: 6 asientos distintos para 6 pedidos.
        assertThat(ofrecidos).hasSize(6);
        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ? "
                + "and estado = 'RETENIDO'", Long.class, itineraryId)).isEqualTo(6L);
        assertThat(eventos("WaitlistSeatOffered", itineraryId)).hasSize(3);
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * 10 asientos: 1-2 vendidos (una retención confirmada), 3 retenido (activa), 4 en oferta, 5 bloqueado,
     * 6 expiró, 7 lo liberó el pasajero, 8 lo liberó el sistema por pago rechazado; 6-10 quedan disponibles.
     */
    private void poblarTodosLosEstados(UUID itineraryId) {
        vender(itineraryId, "1", "2");
        inventory.holdSeats(itineraryId, List.of("3"), UUID.randomUUID(), null);
        inventory.reserveOfferSeats(UUID.randomUUID(), itineraryId, List.of("4"));
        lifecycle.blockSeats(itineraryId, List.of("5"), "Asiento roto", new Actor(UUID.randomUUID(), Actor.ADMIN, null));
        HoldResponse vence = inventory.holdSeats(itineraryId, List.of("6"), UUID.randomUUID(), null);
        jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", vence.holdId());
        inventory.expireHold(vence.holdId());
        liberar(inventory.holdSeats(itineraryId, List.of("7"), UUID.randomUUID(), null));
        HoldResponse rechazado = inventory.holdSeats(itineraryId, List.of("8"), UUID.randomUUID(), null);
        manejar("PaymentFailed", Map.of("holdId", rechazado.holdId(), "motivo", "PAGO_RECHAZADO"));
    }

    private UUID nuevoItinerario(int asientos, UUID companyId, Instant salida) {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, asientos).mapToObj(String::valueOf).toList(),
                salida, companyId);
        return itineraryId;
    }

    private void vender(UUID itineraryId, String... seats) {
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of(seats), UUID.randomUUID(), null);
        inventory.confirmSale(hold.holdId(), UUID.randomUUID());
    }

    /** El pasajero libera su propia retención (como DELETE /seats/holds/{id}). */
    private void liberar(HoldResponse hold) {
        inventory.releaseHold(hold.holdId(), hold.userId(), Hold.LIBERADA_POR_USUARIO);
    }

    private ResultActions anotarse(UUID itineraryId, UUID userId, int cantidad) throws Exception {
        return publico(post("/seats/itineraries/{id}/waitlist", itineraryId), pasajero(userId),
                json(Map.of("cantidad", cantidad)));
    }

    private ResultActions publico(MockHttpServletRequestBuilder request, RequestPostProcessor usuario, String body)
            throws Exception {
        return mockMvc.perform(request.with(usuario).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RequestPostProcessor pasajero(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private static RequestPostProcessor usuario(String role, UUID companyId) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("role", role);
            if (companyId != null) {
                j.claim("companyId", companyId.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private void manejar(String type, Map<String, Object> payload) {
        eventsHandler.handle(new EventEnvelope(UUID.randomUUID().toString(), type, "catalog-service", Instant.now(),
                objectMapper.valueToTree(payload)));
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    /** El turno más reciente del pasajero en el viaje. */
    private Map<String, Object> turno(UUID itineraryId, UUID userId) {
        return jdbc.queryForMap("select estado, hold_id from waitlist where itinerary_id = ? and user_id = ? "
                + "order by creado_en desc limit 1", itineraryId, userId);
    }

    private Map<String, Object> fila(UUID itineraryId, String seat) {
        return jdbc.queryForMap("select estado, user_id, hold_id from seat_states "
                + "where itinerary_id = ? and seat_number = ?", itineraryId, seat);
    }

    private List<JsonNode> eventos(String type, UUID itineraryId) {
        return jdbc.queryForList("select payload::text from outbox where event_type = ? "
                        + "and payload->>'itineraryId' = ?", String.class, type, itineraryId.toString())
                .stream().map(this::leer).toList();
    }

    private JsonNode leer(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
