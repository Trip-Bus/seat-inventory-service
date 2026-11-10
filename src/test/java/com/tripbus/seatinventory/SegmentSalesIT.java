package com.tripbus.seatinventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.common.security.InternalTokenFilter;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.Tramo;
import com.tripbus.seatinventory.domain.VoidReason;
import com.tripbus.seatinventory.events.InventoryEventsHandler;
import com.tripbus.seatinventory.service.Actor;
import com.tripbus.seatinventory.service.InventoryArchiver;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
import com.tripbus.seatinventory.service.WaitlistService;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 6 contra PostgreSQL 16 y Redis 7 reales (Testcontainers) con las migraciones reales (V7: categorías,
 * seat_occupancies con la restricción EXCLUDE; V8: tablas *_archive): categorías de los asientos (US-141), venta por
 * tramos (US-142) y archivo de viajes viejos (HT-08).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
@DirtiesContext
class SegmentSalesIT {

    private static final String DISPONIBLE = "DISPONIBLE";
    private static final String TRAMO = "TRAMO";
    private static final String ESTADO = "$.asientos[%d].estado";

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
    private WaitlistService waitlist;
    @Autowired
    private InventoryInitializer initializer;
    @Autowired
    private InventoryEventsHandler eventsHandler;
    @Autowired
    private InventoryArchiver archiver;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    // ------------------------------------------------------------------ US-141 categorías

    @Test
    void lasCategoriasLleganConElEventoYConElCambioDeBus() throws Exception {
        UUID itineraryId = UUID.randomUUID();
        manejar("ItineraryPublished", Map.of("itineraryId", itineraryId, "seats", List.of(
                Map.of("numero", "1", "categoria", "vip"), Map.of("numero", "2"),
                Map.of("numero", "3", "categoria", "no válida!"))));
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asientos[0].categoria").value("VIP"))
                .andExpect(jsonPath("$.asientos[1].categoria").value("ESTANDAR"))
                .andExpect(jsonPath("$.asientos[2].categoria").value("ESTANDAR"));

        // Solo con seatNumbers queda ESTANDAR, y un evento repetido sin categoría no borra la que ya tiene.
        UUID otro = UUID.randomUUID();
        manejar("ItineraryPublished", Map.of("itineraryId", otro, "seatNumbers", List.of("1")));
        assertThat(categoria(otro, "1")).isEqualTo("ESTANDAR");
        manejar("ItineraryPublished", Map.of("itineraryId", itineraryId, "seatNumbers", List.of("1", "2", "3")));
        assertThat(categoria(itineraryId, "1")).isEqualTo("VIP");

        // El cambio de bus: los que siguen toman la categoría nueva y los nuevos nacen con la suya.
        interno(post("/internal/itineraries/{id}/remap", itineraryId), json(Map.of("capacidad", 4, "seats", List.of(
                Map.of("numero", "1"), Map.of("numero", "2", "categoria", "CAMA"), Map.of("numero", "3"),
                Map.of("numero", "4", "categoria", "PREFERENCIAL")))))
                .andExpect(status().isOk());
        assertThat(categoria(itineraryId, "1")).isEqualTo("VIP");
        assertThat(categoria(itineraryId, "2")).isEqualTo("CAMA");
        assertThat(categoria(itineraryId, "4")).isEqualTo("PREFERENCIAL");
    }

    // ------------------------------------------------------------------ US-142 venta por tramos

    @Test
    void losTramosQueNoSeCruzanSeVendenYLosQueSeCruzanNo() {
        UUID itineraryId = nuevoItinerario(4);
        retener(itineraryId, "1", 1, 3);
        // [1,3) y [3,5) no se cruzan: se baja uno donde se sube el otro.
        retener(itineraryId, "1", 3, 5);
        assertThat(estado(itineraryId, "1")).isEqualTo(TRAMO);

        assertThatThrownBy(() -> retener(itineraryId, "1", 2, 4)).extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        // El recorrido completo se cruza con todo.
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null))
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        inventory.holdSeats(itineraryId, List.of("2"), UUID.randomUUID(), null);
        assertThatThrownBy(() -> retener(itineraryId, "2", 1, 2)).extracting("code").isEqualTo("SEAT_UNAVAILABLE");

        // Cada retención, completa o por tramo, tiene su ocupación; ninguna se cruza.
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies where itinerary_id = ?", Long.class,
                itineraryId)).isEqualTo(3L);
        assertThat(cruces(itineraryId)).isZero();
        // Un tramo mal formado o una oferta por tramo: 400.
        UUID offerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> tres = List.of("3");
        assertThatThrownBy(() -> Tramo.de(3, 3)).extracting("code").isEqualTo("INVALID_SEGMENT");
        assertThatThrownBy(() -> Tramo.de(2, null)).extracting("code").isEqualTo("INVALID_SEGMENT");
        Tramo tramo = new Tramo(1, 2);
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, tres, userId, offerId, tramo))
                .extracting("code").isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void elMapaYLaDisponibilidadRespondenPorTramo() throws Exception {
        UUID itineraryId = nuevoItinerario(3);
        UUID ana = UUID.randomUUID();
        inventory.holdSeats(itineraryId, List.of("1"), ana, null, new Tramo(1, 3));
        vender(itineraryId, "2", 2, 4);
        lifecycle.blockSeats(itineraryId, List.of("3"), "Roto",
                new Actor(UUID.randomUUID(), Actor.ADMIN, null));

        // Sin tramo: el estado del recorrido completo, como siempre (TRAMO = ocupado en parte).
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId))
                .andExpect(jsonPath(ESTADO.formatted(0)).value(TRAMO))
                .andExpect(jsonPath(ESTADO.formatted(1)).value(TRAMO))
                .andExpect(jsonPath("$.resumen.enTramos").value(2))
                .andExpect(jsonPath("$.resumen.disponibles").value(0));
        // [1,2): el 1 está retenido (y es de Ana), el 2 está libre.
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId).param("from", "1").param("to", "2")
                        .with(pasajero(ana)))
                .andExpect(jsonPath(ESTADO.formatted(0)).value("RETENIDO"))
                .andExpect(jsonPath("$.asientos[0].propio").value(true))
                .andExpect(jsonPath(ESTADO.formatted(1)).value(DISPONIBLE))
                .andExpect(jsonPath(ESTADO.formatted(2)).value("BLOQUEADO"))
                .andExpect(jsonPath("$.resumen.disponibles").value(1));
        // [3,4): el 1 está libre y el 2 vendido.
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId).param("from", "3").param("to", "4"))
                .andExpect(jsonPath(ESTADO.formatted(0)).value(DISPONIBLE))
                .andExpect(jsonPath(ESTADO.formatted(1)).value("VENDIDO"));

        mockMvc.perform(get("/seats/availability").param("itineraryIds", itineraryId.toString())
                        .param("from", "4").param("to", "6"))
                .andExpect(jsonPath("$[0].disponibles").value(2))
                .andExpect(jsonPath("$[0].total").value(2));
        mockMvc.perform(get("/seats/availability").param("itineraryIds", itineraryId.toString())
                        .param("from", "2").param("to", "3"))
                .andExpect(jsonPath("$[0].disponibles").value(0));
        // Sin tramo: ningún asiento está libre para todo el recorrido.
        mockMvc.perform(get("/seats/availability").param("itineraryIds", itineraryId.toString()))
                .andExpect(jsonPath("$[0].disponibles").value(0))
                .andExpect(jsonPath("$[0].total").value(2));
        mockMvc.perform(get("/seats/availability").param("itineraryIds", itineraryId.toString()).param("from", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEGMENT"));
    }

    @Test
    void retenerPorTramoPorLaApiYConfirmarLiberarYVencerRespetanElTramo() throws Exception {
        UUID itineraryId = nuevoItinerario(3);
        UUID ana = UUID.randomUUID();
        mockMvc.perform(post("/seats/holds").with(pasajero(ana)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("itineraryId", itineraryId, "seatNumbers", List.of("1"),
                                "fromOrden", 2, "toOrden", 5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromOrden").value(2))
                .andExpect(jsonPath("$.toOrden").value(5));
        UUID holdAna = jdbc.queryForObject("select id from holds where itinerary_id = ? and user_id = ?", UUID.class,
                itineraryId, ana);
        HoldResponse interno = inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null,
                new Tramo(5, 7));

        // Confirmar: la ocupación pasa a VENDIDO y el asiento sigue TRAMO.
        inventory.confirmSale(holdAna, UUID.randomUUID());
        assertThat(ocupacion(holdAna)).isEqualTo("VENDIDO");
        assertThat(estado(itineraryId, "1")).isEqualTo(TRAMO);
        assertThat(eventoTramo("SeatSold", holdAna)).isEqualTo("2-5");

        // Liberar el otro tramo: le queda la venta de Ana, sigue TRAMO.
        inventory.releaseHold(interno.holdId(), null, "PRUEBA");
        assertThat(estado(itineraryId, "1")).isEqualTo(TRAMO);
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies where hold_id = ?", Long.class,
                interno.holdId())).isZero();

        // Vencer un tramo de un asiento que no tiene otra ocupación: vuelve a DISPONIBLE.
        HoldResponse vence = inventory.holdSeats(itineraryId, List.of("2"), UUID.randomUUID(), null,
                new Tramo(1, 2));
        jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", vence.holdId());
        assertThat(inventory.expireHold(vence.holdId())).isTrue();
        assertThat(estado(itineraryId, "2")).isEqualTo(DISPONIBLE);
        assertThat(jdbc.queryForObject("select desde_orden || '-' || hasta_orden from holds where id = ?",
                String.class, vence.holdId())).isEqualTo("1-2");
    }

    @Test
    void anularYCambiarDeAsientoPorTramo() throws Exception {
        UUID itineraryId = nuevoItinerario(4);
        UUID bookingId = UUID.randomUUID();
        HoldResponse venta = vender(itineraryId, bookingId, "1", 2, 4);
        vender(itineraryId, UUID.randomUUID(), "1", 4, 6);
        vender(itineraryId, UUID.randomUUID(), "3", 1, 3);

        // Cambiar el 1 por el 3 en [2,4): el 3 está vendido en [1,3), que se cruza → 409.
        String alTres = json(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "fromSeat", "1",
                "toSeat", "3"));
        interno(post("/internal/sales/swap"), alTres)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_UNAVAILABLE"));
        // Al 2, libre en ese tramo: la venta se mueve y el 1 sigue TRAMO (le queda la venta de [4,6)).
        String alDos = json(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "fromSeat", "1",
                "toSeat", "2"));
        interno(post("/internal/sales/swap"), alDos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(venta.holdId().toString()));
        assertThat(jdbc.queryForObject("select seat_number from seat_occupancies where hold_id = ?", String.class,
                venta.holdId())).isEqualTo("2");
        assertThat(estado(itineraryId, "1")).isEqualTo(TRAMO);
        assertThat(estado(itineraryId, "2")).isEqualTo(TRAMO);
        // Repetirlo responde el estado actual.
        interno(post("/internal/sales/swap"), alDos).andExpect(status().isOk());

        // Anular: la ocupación se borra y el 2 vuelve a DISPONIBLE; repetirla no falla.
        String anular = json(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "seatNumbers", List.of("2"),
                "motivo", "REEMBOLSO"));
        interno(post("/internal/sales/void"), anular)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anulados[0]").value("2"));
        assertThat(estado(itineraryId, "2")).isEqualTo(DISPONIBLE);
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies where hold_id = ?", Long.class,
                venta.holdId())).isZero();
        interno(post("/internal/sales/void"), anular)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anulados").isEmpty());
        // Otra reserva no anula una venta por tramo que no es suya.
        UUID otraReserva = UUID.randomUUID();
        List<String> uno = List.of("1");
        assertThatThrownBy(() -> lifecycle.voidSale(itineraryId, otraReserva, uno, VoidReason.REEMBOLSO))
                .extracting("code").isEqualTo("SEAT_NOT_OWNED");
        // Un asiento con ventas por tramo está ocupado: no se puede quitar en un cambio de bus.
        interno(post("/internal/itineraries/{id}/remap", itineraryId), json(Map.of("capacidad", 3,
                "seatNumbers", List.of("2", "3", "4"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_CONFLICT"));
    }

    @Test
    void recorridoCompletoContraTramoYLaVentaCompletaTambienOcupa() throws Exception {
        UUID itineraryId = nuevoItinerario(2);
        UUID bookingId = UUID.randomUUID();
        HoldResponse completa = vender(itineraryId, bookingId, "1", null, null);
        assertThat(jdbc.queryForMap("select desde_orden, hasta_orden, estado from seat_occupancies where hold_id = ?",
                completa.holdId())).containsEntry("desde_orden", 1).containsEntry("hasta_orden", 32767)
                .containsEntry("estado", "VENDIDO");
        assertThatThrownBy(() -> retener(itineraryId, "1", 5, 6)).extracting("code").isEqualTo("SEAT_UNAVAILABLE");

        // El cambio de asiento del recorrido completo mueve también la ocupación; anular la borra.
        interno(post("/internal/sales/swap"), json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "fromSeat", "1", "toSeat", "2"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select seat_number from seat_occupancies where hold_id = ?", String.class,
                completa.holdId())).isEqualTo("2");
        retener(itineraryId, "1", 5, 6);
        lifecycle.voidSale(itineraryId, bookingId, List.of("2"), VoidReason.CAMBIO_BUS);
        assertThat(estado(itineraryId, "2")).isEqualTo(DISPONIBLE);
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies where hold_id = ?", Long.class,
                completa.holdId())).isZero();
    }

    @Test
    void laRestriccionExcludeRechazaUnCruceAunqueFalleLaAplicacion() {
        UUID itineraryId = nuevoItinerario(2);
        HoldResponse hold = retener(itineraryId, "1", 1, 4);

        // Directo en la base, saltándose la aplicación: un tramo que se cruza no entra.
        assertThatThrownBy(() -> insertarOcupacion(itineraryId, "1", 3, 6, hold.holdId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_seat_occupancies_sin_cruce");
        // Uno que empieza donde termina el otro sí.
        insertarOcupacion(itineraryId, "1", 4, 6, hold.holdId());

        // La aplicación "falla": el asiento 2 figura DISPONIBLE pero alguien ya lo ocupa en la base. La BD rechaza la
        // retención y no queda nada a medias.
        insertarOcupacion(itineraryId, "2", 1, 32767, hold.holdId());
        List<String> dos = List.of("2");
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, dos, userId, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        assertThat(estado(itineraryId, "2")).isEqualTo(DISPONIBLE);
        assertThat(jdbc.queryForObject("select count(*) from holds where itinerary_id = ? and user_id = ?",
                Long.class, itineraryId, userId)).isZero();
    }

    @Test
    void laListaDeEsperaUsaElTramoDeQuienSeAnoto() {
        UUID itineraryId = nuevoItinerario(1);
        HoldResponse ocupado = retener(itineraryId, "1", 1, 4);
        UUID ana = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tramo libre = new Tramo(4, 6);
        // En [4,6) el asiento está libre: que lo retenga.
        assertThatThrownBy(() -> waitlist.anotarse(itineraryId, userId, 1, libre))
                .extracting("code").isEqualTo("SEATS_AVAILABLE");
        waitlist.anotarse(itineraryId, ana, 1, new Tramo(2, 3));

        inventory.releaseHold(ocupado.holdId(), ocupado.userId(), Hold.LIBERADA_POR_USUARIO);
        await().atMost(Duration.ofSeconds(15)).until(() -> "OFRECIDO".equals(jdbc.queryForObject(
                "select estado from waitlist where itinerary_id = ? and user_id = ?", String.class, itineraryId, ana)));
        UUID holdAna = jdbc.queryForObject("select hold_id from waitlist where itinerary_id = ? and user_id = ?",
                UUID.class, itineraryId, ana);
        assertThat(jdbc.queryForObject("select desde_orden || '-' || hasta_orden from holds where id = ?",
                String.class, holdAna)).isEqualTo("2-3");
        assertThat(estado(itineraryId, "1")).isEqualTo(TRAMO);
    }

    @Test
    void doscientosHilosPorElMismoAsientoConTramosQueSeCruzanYQueNo() throws Exception {
        UUID itineraryId = nuevoItinerario(1);
        // 100 tramos que se cruzan entre sí (todos pasan por [5,6)) y 100 de una parada, de 10 en adelante, que no
        // se cruzan con nada.
        List<Tramo> cruzan = IntStream.range(0, 100).mapToObj(i -> new Tramo(1 + i % 5, 6 + i % 4)).toList();
        List<Tramo> separados = IntStream.range(0, 100).mapToObj(i -> new Tramo(10 + i, 11 + i)).toList();
        List<Tramo> todos = new ArrayList<>(cruzan);
        todos.addAll(separados);

        ExecutorService pool = Executors.newFixedThreadPool(todos.size());
        CountDownLatch salida = new CountDownLatch(1);
        AtomicInteger ganadoresQueCruzan = new AtomicInteger();
        AtomicInteger ganadoresSeparados = new AtomicInteger();
        List<Future<?>> tareas = new ArrayList<>();
        try {
            for (Tramo tramo : todos) {
                boolean cruza = tramo.cruza(new Tramo(5, 6));
                tareas.add(pool.submit(() -> {
                    salida.await();
                    try {
                        inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null, tramo);
                        (cruza ? ganadoresQueCruzan : ganadoresSeparados).incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getStatus().value()).isEqualTo(409);
                    }
                    return null;
                }));
            }
            salida.countDown();
            for (Future<?> tarea : tareas) {
                tarea.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ganadoresQueCruzan.get()).isEqualTo(1);
        assertThat(ganadoresSeparados.get()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies where itinerary_id = ?", Long.class,
                itineraryId)).isEqualTo(101L);
        assertThat(cruces(itineraryId)).isZero();
    }

    // ------------------------------------------------------------------ HT-08 archivo

    @Test
    void losViajesViejosSeArchivanYSuOcupacionSigueRespondiendo() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID viejo = UUID.randomUUID();
        initializer.initialize(viejo, List.of("1", "2", "3", "4"), Instant.now().minus(Duration.ofDays(100)),
                companyId);
        vender(viejo, UUID.randomUUID(), "1", null, null);
        vender(viejo, UUID.randomUUID(), "2", 1, 3);
        HoldResponse abandonada = inventory.holdSeats(viejo, List.of("3"), UUID.randomUUID(), null);
        inventory.releaseHold(abandonada.holdId(), abandonada.userId(), Hold.LIBERADA_POR_USUARIO);
        UUID reciente = UUID.randomUUID();
        initializer.initialize(reciente, List.of("1"), Instant.now().minus(Duration.ofDays(10)), companyId);

        assertThat(archiver.archivarViejos()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from itineraries where id = ?", Long.class, viejo)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ?", Long.class, viejo))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from seat_states_archive where itinerary_id = ?", Long.class,
                viejo)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select count(*) from holds_archive where itinerary_id = ?", Long.class,
                viejo)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from hold_seats_archive hs join holds_archive h "
                + "on h.id = hs.hold_id where h.itinerary_id = ?", Long.class, viejo)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from seat_occupancies_archive where itinerary_id = ?",
                Long.class, viejo)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from itineraries where id = ?", Long.class, reciente))
                .isEqualTo(1L);

        mockMvc.perform(get("/internal/occupancy").header(InternalTokenFilter.HEADER, internalToken)
                        .param("itineraryIds", viejo + "," + reciente))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itineraryId").value(viejo.toString()))
                .andExpect(jsonPath("$[0].capacidad").value(4))
                .andExpect(jsonPath("$[0].vendidos").value(1))
                .andExpect(jsonPath("$[0].enTramos").value(1))
                .andExpect(jsonPath("$[0].retenciones.creadas").value(3))
                .andExpect(jsonPath("$[0].retenciones.liberadasPorUsuario").value(1))
                .andExpect(jsonPath("$[1].itineraryId").value(reciente.toString()));
        mockMvc.perform(get("/seats/itineraries/{id}/occupancy", viejo).with(usuario("OPERADOR", companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendidos").value(1));
        // El viaje archivado no se vuelve a inicializar desde catalog.
        mockMvc.perform(get("/seats/itineraries/{id}", viejo))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_ARCHIVED"));
        // Volver a correr el job no encuentra nada más que archivar.
        assertThat(archiver.archivarViejos()).isZero();
    }

    // ------------------------------------------------------------------ utilidades

    private UUID nuevoItinerario(int asientos) {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, asientos).mapToObj(String::valueOf).toList(),
                null);
        return itineraryId;
    }

    private HoldResponse retener(UUID itineraryId, String seat, int desde, int hasta) {
        return inventory.holdSeats(itineraryId, List.of(seat), UUID.randomUUID(), null, new Tramo(desde, hasta));
    }

    private HoldResponse vender(UUID itineraryId, String seat, int desde, int hasta) {
        return vender(itineraryId, UUID.randomUUID(), seat, desde, hasta);
    }

    private HoldResponse vender(UUID itineraryId, UUID bookingId, String seat, Integer desde, Integer hasta) {
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of(seat), UUID.randomUUID(), null,
                Tramo.de(desde, hasta));
        return inventory.confirmSale(hold.holdId(), bookingId);
    }

    private void insertarOcupacion(UUID itineraryId, String seat, int desde, int hasta, UUID holdId) {
        jdbc.update("""
                insert into seat_occupancies (id, itinerary_id, seat_number, desde_orden, hasta_orden, estado, hold_id,
                                              user_id, creado_en)
                values (?, ?, ?, ?, ?, 'VENDIDO', ?, ?, now())
                """, UUID.randomUUID(), itineraryId, seat, desde, hasta, holdId, UUID.randomUUID());
    }

    /** Pares de ocupaciones del mismo asiento que se cruzan (la invariante dice que nunca hay). */
    private long cruces(UUID itineraryId) {
        return jdbc.queryForObject("""
                select count(*) from seat_occupancies a join seat_occupancies b
                  on a.itinerary_id = b.itinerary_id and a.seat_number = b.seat_number and a.id < b.id
                 and int4range(a.desde_orden, a.hasta_orden) && int4range(b.desde_orden, b.hasta_orden)
                where a.itinerary_id = ?
                """, Long.class, itineraryId);
    }

    private String estado(UUID itineraryId, String seat) {
        return jdbc.queryForObject("select estado from seat_states where itinerary_id = ? and seat_number = ?",
                String.class, itineraryId, seat);
    }

    private String categoria(UUID itineraryId, String seat) {
        return jdbc.queryForObject("select categoria from seat_states where itinerary_id = ? and seat_number = ?",
                String.class, itineraryId, seat);
    }

    private String ocupacion(UUID holdId) {
        return jdbc.queryForObject("select estado from seat_occupancies where hold_id = ?", String.class, holdId);
    }

    private String eventoTramo(String type, UUID holdId) {
        return jdbc.queryForObject("select (payload->>'desdeOrden') || '-' || (payload->>'hastaOrden') from outbox "
                + "where event_type = ? and payload->>'holdId' = ?", String.class, type, holdId.toString());
    }

    private ResultActions interno(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.header(InternalTokenFilter.HEADER, internalToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RequestPostProcessor pasajero(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private static RequestPostProcessor usuario(String role, UUID companyId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", role)
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private void manejar(String type, Map<String, Object> payload) {
        eventsHandler.handle(new EventEnvelope(UUID.randomUUID().toString(), type, "catalog-service", Instant.now(),
                objectMapper.valueToTree(payload)));
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
