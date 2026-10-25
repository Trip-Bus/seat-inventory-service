package com.tripbus.seatinventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.common.security.InternalTokenFilter;
import com.tripbus.seatinventory.domain.VoidReason;
import com.tripbus.seatinventory.events.InventoryEventsHandler;
import com.tripbus.seatinventory.service.Actor;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3, ciclo del asiento: anular (US-151), cambiar de asiento (US-139), bloquear (US-112) y cambiar el
 * bus del viaje (US-119), contra PostgreSQL 16 y Redis 7 reales (Testcontainers) con las migraciones Flyway
 * reales, incluido el trigger ampliado de V4.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
@DirtiesContext
class SeatLifecycleIT {

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
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    // ------------------------------------------------------------------ US-151 anular

    @Test
    void anularDevuelveElAsientoAlInventarioYEsIdempotente() throws Exception {
        UUID itineraryId = nuevoItinerario(10, null);
        UUID bookingId = UUID.randomUUID();
        HoldResponse hold = vender(itineraryId, bookingId, "3", "4");
        String body = json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "seatNumbers", List.of("3"), "motivo", "REEMBOLSO"));

        interno(post("/internal/sales/void"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anulados", contains("3")))
                .andExpect(jsonPath("$.asientos[0].estado").value("DISPONIBLE"));

        assertThat(fila(itineraryId, "3")).containsEntry("estado", "DISPONIBLE")
                .containsEntry("user_id", null).containsEntry("hold_id", null).containsEntry("vendido_en", null);
        assertThat(estado(itineraryId, "4")).isEqualTo("VENDIDO");
        assertThat(jdbc.queryForList("select seat_number from hold_seats where hold_id = ?", String.class,
                hold.holdId())).containsExactly("4");
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                        + "and seat_number = '3' and origen = 'VOID_REEMBOLSO' and hold_id = ?", Long.class,
                itineraryId, hold.holdId())).isEqualTo(1L);
        assertThat(eventos("SeatReleased", itineraryId)).anySatisfy(p -> {
            assertThat(p.path("bookingId").asText()).isEqualTo(bookingId.toString());
            assertThat(p.path("motivo").asText()).isEqualTo("REEMBOLSO");
            assertThat(p.path("seatNumbers").get(0).asText()).isEqualTo("3");
        });
        assertThat(eventos("SeatStateChanged", itineraryId)).anySatisfy(p -> {
            assertThat(p.path("seatNumber").asText()).isEqualTo("3");
            assertThat(p.path("estadoAnterior").asText()).isEqualTo("VENDIDO");
            assertThat(p.path("estadoNuevo").asText()).isEqualTo("DISPONIBLE");
        });

        // Repetir la misma anulación no falla ni vuelve a auditar.
        interno(post("/internal/sales/void"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anulados").isEmpty())
                .andExpect(jsonPath("$.asientos[0].estado").value("DISPONIBLE"));
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                + "and origen like 'VOID%'", Long.class, itineraryId)).isEqualTo(1L);

        // Aunque el asiento se haya revendido después, repetirla responde el estado actual.
        vender(itineraryId, UUID.randomUUID(), "3");
        interno(post("/internal/sales/void"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asientos[0].estado").value("VENDIDO"));
    }

    @Test
    void soloSeAnulaLoVendidoAEsaReserva() throws Exception {
        UUID itineraryId = nuevoItinerario(10, null);
        UUID bookingId = UUID.randomUUID();
        vender(itineraryId, bookingId, "1");
        vender(itineraryId, UUID.randomUUID(), "2");

        interno(post("/internal/sales/void"), json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "seatNumbers", List.of("1", "2"), "motivo", "CANCELACION_EMPRESA")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_NOT_OWNED"))
                .andExpect(jsonPath("$.error.details.asientos", contains("2")));
        // Todo o nada: el asiento propio tampoco se anuló.
        assertThat(estado(itineraryId, "1")).isEqualTo("VENDIDO");

        // Un asiento que esta reserva nunca compró tampoco se "anula", ni otra reserva anula el suyo.
        List<String> nuncaComprado = List.of("5");
        List<String> propio = List.of("1");
        UUID otraReserva = UUID.randomUUID();
        assertThatThrownBy(() -> lifecycle.voidSale(itineraryId, bookingId, nuncaComprado, VoidReason.REEMBOLSO))
                .extracting("code").isEqualTo("SEAT_NOT_OWNED");
        assertThatThrownBy(() -> lifecycle.voidSale(itineraryId, otraReserva, propio, VoidReason.CAMBIO_BUS))
                .extracting("code").isEqualTo("SEAT_NOT_OWNED");
    }

    @Test
    void anularValidaLaSolicitud() throws Exception {
        UUID itineraryId = nuevoItinerario(5, null);
        interno(post("/internal/sales/void"), json(Map.of("itineraryId", itineraryId,
                "bookingId", UUID.randomUUID(), "seatNumbers", List.of("1"), "motivo", "PORQUE_SI")))
                .andExpect(status().isBadRequest());
        interno(post("/internal/sales/void"), json(Map.of("itineraryId", itineraryId,
                "bookingId", UUID.randomUUID(), "seatNumbers", List.of("99"), "motivo", "REEMBOLSO")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SEAT_NOT_FOUND"));
        interno(post("/internal/sales/void"), json(Map.of("itineraryId", UUID.randomUUID(),
                "bookingId", UUID.randomUUID(), "seatNumbers", List.of("1"), "motivo", "REEMBOLSO")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        UUID bookingId = UUID.randomUUID();
        List<String> enBlanco = List.of(" ");
        assertThatThrownBy(() -> lifecycle.voidSale(itineraryId, bookingId, enBlanco, VoidReason.REEMBOLSO))
                .extracting("code").isEqualTo("INVALID_SEATS");
        // Sin token interno no se llega.
        mockMvc.perform(post("/internal/sales/void").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ US-139 cambiar de asiento

    @Test
    void cambiarDeAsientoMueveLaVentaEnUnaSolaTransaccion() throws Exception {
        UUID itineraryId = nuevoItinerario(10, null);
        UUID bookingId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        HoldResponse hold = vender(itineraryId, bookingId, user, "2");
        String body = json(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "fromSeat", "2",
                "toSeat", "8"));

        interno(post("/internal/sales/swap"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(hold.holdId().toString()))
                .andExpect(jsonPath("$.asientos[0].seatNumber").value("2"))
                .andExpect(jsonPath("$.asientos[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.asientos[1].estado").value("VENDIDO"));

        assertThat(fila(itineraryId, "8")).containsEntry("estado", "VENDIDO").containsEntry("user_id", user)
                .containsEntry("hold_id", hold.holdId());
        assertThat(fila(itineraryId, "2")).containsEntry("estado", "DISPONIBLE").containsEntry("user_id", null);
        assertThat(jdbc.queryForList("select seat_number from hold_seats where hold_id = ?", String.class,
                hold.holdId())).containsExactly("8");
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                + "and origen = 'SWAP'", Long.class, itineraryId)).isEqualTo(2L);
        assertThat(eventos("SeatStateChanged", itineraryId))
                .anySatisfy(p -> assertThat(p.path("seatNumber").asText() + p.path("estadoNuevo").asText())
                        .isEqualTo("8VENDIDO"))
                .anySatisfy(p -> assertThat(p.path("seatNumber").asText() + p.path("estadoNuevo").asText())
                        .isEqualTo("2DISPONIBLE"));
        assertThat(eventos("SeatReleased", itineraryId))
                .anySatisfy(p -> assertThat(p.path("motivo").asText()).isEqualTo("CAMBIO_DE_ASIENTO"));

        // Repetir el mismo cambio responde el estado actual.
        interno(post("/internal/sales/swap"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asientos[1].estado").value("VENDIDO"));
        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ? "
                + "and estado = 'VENDIDO'", Long.class, itineraryId)).isEqualTo(1L);
    }

    @Test
    void cambiarDeAsientoRechazaDestinosOcupadosYOrigenesAjenos() throws Exception {
        UUID itineraryId = nuevoItinerario(10, null);
        UUID bookingId = UUID.randomUUID();
        vender(itineraryId, bookingId, "1");
        vender(itineraryId, UUID.randomUUID(), "2");
        inventory.holdSeats(itineraryId, List.of("3"), UUID.randomUUID(), null);

        interno(post("/internal/sales/swap"), json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "fromSeat", "1", "toSeat", "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_UNAVAILABLE"));
        interno(post("/internal/sales/swap"), json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "fromSeat", "2", "toSeat", "5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_NOT_OWNED"));
        interno(post("/internal/sales/swap"), json(Map.of("itineraryId", itineraryId, "bookingId", bookingId,
                "fromSeat", "1", "toSeat", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAME_SEAT"));
        assertThatThrownBy(() -> lifecycle.swapSeat(itineraryId, bookingId, "1", null))
                .extracting("code").isEqualTo("INVALID_SEATS");

        assertThat(estado(itineraryId, "1")).isEqualTo("VENDIDO");
        assertThat(estado(itineraryId, "5")).isEqualTo("DISPONIBLE");

        inventory.onItineraryCancelled(itineraryId, "PRUEBA");
        assertThatThrownBy(() -> lifecycle.swapSeat(itineraryId, bookingId, "1", "6"))
                .extracting("code").isEqualTo("ITINERARY_CANCELLED");
    }

    @Test
    void cienReservasPorElMismoAsientoLibreSoloUnaLoObtiene() throws Exception {
        int hilos = 100;
        UUID itineraryId = nuevoItinerario(hilos + 10, null);
        String destino = String.valueOf(hilos + 5);
        List<UUID> bookings = new ArrayList<>();
        for (int i = 1; i <= hilos; i++) {
            UUID bookingId = UUID.randomUUID();
            vender(itineraryId, bookingId, String.valueOf(i));
            bookings.add(bookingId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        AtomicInteger exitos = new AtomicInteger();
        ConcurrentLinkedQueue<String> rechazos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> inesperados = new ConcurrentLinkedQueue<>();
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                UUID bookingId = bookings.get(i);
                String origen = String.valueOf(i + 1);
                futuros.add(pool.submit(() -> {
                    salida.await();
                    try {
                        lifecycle.swapSeat(itineraryId, bookingId, origen, destino);
                        exitos.incrementAndGet();
                    } catch (ApiException e) {
                        rechazos.add(e.getCode());
                    } catch (Exception e) {
                        inesperados.add(e);
                    }
                    return null;
                }));
            }
            salida.countDown();
            for (Future<?> f : futuros) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(inesperados).isEmpty();
        assertThat(exitos.get()).isEqualTo(1);
        // Cada rechazo es uno de estos dos (cuántos de cada uno depende del orden en que llegan los hilos).
        assertThat(rechazos).hasSize(hilos - 1).isSubsetOf("SEAT_UNAVAILABLE", "SEAT_LOCKED");
        assertThat(estado(itineraryId, destino)).isEqualTo("VENDIDO");
        // Nadie quedó con cero ni con dos asientos: siguen vendidos exactamente 100.
        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ? "
                + "and estado = 'VENDIDO'", Long.class, itineraryId)).isEqualTo((long) hilos);
        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ? "
                + "and estado = 'DISPONIBLE' and cast(seat_number as integer) <= ?", Long.class, itineraryId,
                hilos)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ US-112 bloquear

    @Test
    void elOperadorDeLaEmpresaBloqueaYDesbloqueaAsientosDisponibles() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(10, companyId);
        RequestPostProcessor operador = usuario("OPERADOR", companyId);

        publico(post("/seats/itineraries/{id}/blocks", itineraryId), operador,
                json(Map.of("seatNumbers", List.of("1", "2"), "motivo", "Asiento del conductor auxiliar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cambiados", contains("1", "2")));
        // Repetir no falla: ya estaban bloqueados.
        publico(post("/seats/itineraries/{id}/blocks", itineraryId), operador,
                json(Map.of("seatNumbers", List.of("2"), "motivo", "Otro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinCambio", contains("2")));

        assertThat(fila(itineraryId, "1")).containsEntry("estado", "BLOQUEADO")
                .containsEntry("bloqueo_motivo", "Asiento del conductor auxiliar");
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                + "and origen = 'BLOCK'", Long.class, itineraryId)).isEqualTo(2L);
        assertThat(eventos("SeatStateChanged", itineraryId))
                .anySatisfy(p -> assertThat(p.path("estadoNuevo").asText()).isEqualTo("BLOQUEADO"));

        // Aparece como BLOQUEADO; el motivo solo lo ve quien gestiona el viaje.
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumen.bloqueados").value(2))
                .andExpect(jsonPath("$.asientos[0].estado").value("BLOQUEADO"))
                .andExpect(jsonPath("$.asientos[0].motivoBloqueo").doesNotExist());
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId).with(usuario("PASAJERO", null)))
                .andExpect(jsonPath("$.asientos[0].motivoBloqueo").doesNotExist());
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId).with(usuario("OPERADOR", UUID.randomUUID())))
                .andExpect(jsonPath("$.asientos[0].motivoBloqueo").doesNotExist());
        mockMvc.perform(get("/seats/itineraries/{id}", itineraryId).with(operador))
                .andExpect(jsonPath("$.asientos[0].motivoBloqueo").value("Asiento del conductor auxiliar"));
        mockMvc.perform(get("/seats/itineraries/{id}/{n}", itineraryId, "1").with(usuario("ADMIN", null)))
                .andExpect(jsonPath("$.estado").value("BLOQUEADO"))
                .andExpect(jsonPath("$.motivoBloqueo").value("Asiento del conductor auxiliar"));

        publico(delete("/seats/itineraries/{id}/blocks", itineraryId), operador,
                json(Map.of("seatNumbers", List.of("1", "3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cambiados", contains("1")))
                .andExpect(jsonPath("$.sinCambio", contains("3")));
        assertThat(fila(itineraryId, "1")).containsEntry("estado", "DISPONIBLE").containsEntry("bloqueo_motivo", null);
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                + "and origen = 'UNBLOCK'", Long.class, itineraryId)).isEqualTo(1L);
    }

    @Test
    void soloElOperadorDeLaEmpresaOElAdminPuedenBloquear() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(5, companyId);
        String body = json(Map.of("seatNumbers", List.of("1"), "motivo", "Mantenimiento"));

        publico(post("/seats/itineraries/{id}/blocks", itineraryId), usuario("OPERADOR", UUID.randomUUID()), body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ITINERARY_MANAGER"));
        publico(post("/seats/itineraries/{id}/blocks", itineraryId), usuario("PASAJERO", companyId), body)
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/seats/itineraries/{id}/blocks", itineraryId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        publico(post("/seats/itineraries/{id}/blocks", itineraryId), usuario("ADMIN", null), body)
                .andExpect(status().isOk());

        // Itinerario anterior a V4 (sin empresa): solo el ADMIN.
        UUID viejo = nuevoItinerario(5, null);
        publico(post("/seats/itineraries/{id}/blocks", viejo), usuario("OPERADOR", companyId), body)
                .andExpect(status().isForbidden());
        publico(delete("/seats/itineraries/{id}/blocks", viejo), usuario("OPERADOR", companyId),
                json(Map.of("seatNumbers", List.of("1"))))
                .andExpect(status().isForbidden());
        publico(post("/seats/itineraries/{id}/blocks", viejo), usuario("ADMIN", null), body)
                .andExpect(status().isOk());

        publico(post("/seats/itineraries/{id}/blocks", itineraryId), usuario("ADMIN", null),
                json(Map.of("seatNumbers", List.of("1"), "motivo", " ")))
                .andExpect(status().isBadRequest());
        List<String> asiento = List.of("2");
        Actor admin = new Actor(UUID.randomUUID(), "ADMIN", null);
        assertThatThrownBy(() -> lifecycle.blockSeats(itineraryId, asiento, null, admin))
                .extracting("code").isEqualTo("INVALID_REASON");
    }

    @Test
    void soloSeBloqueaLoDisponibleYLoBloqueadoNoSeVendeNiSeOferta() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(10, companyId);
        RequestPostProcessor operador = usuario("OPERADOR", companyId);
        inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null);
        vender(itineraryId, UUID.randomUUID(), "2");
        inventory.reserveOfferSeats(UUID.randomUUID(), itineraryId, List.of("3"));

        publico(post("/seats/itineraries/{id}/blocks", itineraryId), operador,
                json(Map.of("seatNumbers", List.of("1", "2", "3", "4"), "motivo", "Daño")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.asientos", contains("1", "2", "3")));
        assertThat(estado(itineraryId, "4")).isEqualTo("DISPONIBLE");

        publico(post("/seats/itineraries/{id}/blocks", itineraryId), operador,
                json(Map.of("seatNumbers", List.of("4", "5"), "motivo", "Daño")))
                .andExpect(status().isOk());
        List<String> bloqueado = List.of("4");
        UUID pasajero = UUID.randomUUID();
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, bloqueado, pasajero, null))
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        UUID offerId = UUID.randomUUID();
        assertThat(inventory.reserveOfferSeats(offerId, itineraryId, List.of("5", "6")).omitidos())
                .containsExactly("5");

        // No cuentan en la disponibilidad (ni en el total): 10 asientos - 2 bloqueados.
        mockMvc.perform(get("/seats/availability").param("itineraryIds", itineraryId.toString()))
                .andExpect(jsonPath("$[0].total").value(8))
                .andExpect(jsonPath("$[0].disponibles").value(6));

        // La base de datos tampoco deja bloquear un asiento vendido, ni un bloqueo sin motivo.
        assertThatThrownBy(() -> jdbc.update("update seat_states set estado = 'BLOQUEADO', bloqueo_motivo = 'x' "
                + "where itinerary_id = ? and seat_number = '2'", itineraryId)).hasMessageContaining("DOUBLE_SALE");
        assertThatThrownBy(() -> jdbc.update("update seat_states set estado = 'BLOQUEADO' "
                + "where itinerary_id = ? and seat_number = '7'", itineraryId))
                .hasMessageContaining("ck_seat_states_bloqueado");
    }

    // ------------------------------------------------------------------ US-119 cambiar el bus

    @Test
    void cambiarElBusAgregaYBorraAsientosLibres() throws Exception {
        UUID itineraryId = nuevoItinerario(6, null);
        vender(itineraryId, UUID.randomUUID(), "2");

        interno(post("/internal/itineraries/{id}/remap", itineraryId),
                json(Map.of("seatNumbers", List.of("1", "2", "3", "4", "7", "8"), "capacidad", 6)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregados", contains("7", "8")))
                .andExpect(jsonPath("$.eliminados", contains("5", "6")));

        assertThat(jdbc.queryForList("select seat_number from seat_states where itinerary_id = ? "
                + "order by cast(seat_number as integer)", String.class, itineraryId))
                .containsExactly("1", "2", "3", "4", "7", "8");
        assertThat(estado(itineraryId, "2")).isEqualTo("VENDIDO");
        assertThat(estado(itineraryId, "7")).isEqualTo("DISPONIBLE");
        assertThat(jdbc.queryForObject("select count(*) from seat_audit_log where itinerary_id = ? "
                + "and origen = 'REMAP'", Long.class, itineraryId)).isEqualTo(2L);
        // Los asientos nuevos se pueden retener.
        inventory.holdSeats(itineraryId, List.of("8"), UUID.randomUUID(), null);

        // Aplicarlo otra vez no cambia nada.
        interno(post("/internal/itineraries/{id}/remap", itineraryId),
                json(Map.of("seatNumbers", List.of("1", "2", "3", "4", "7", "8"), "capacidad", 6)))
                .andExpect(jsonPath("$.agregados").isEmpty())
                .andExpect(jsonPath("$.eliminados").isEmpty());
    }

    @Test
    void cambiarElBusSinUnAsientoOcupadoNoCambiaNadaYDevuelveLosConflictos() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID itineraryId = nuevoItinerario(8, companyId);
        vender(itineraryId, UUID.randomUUID(), "5");
        inventory.holdSeats(itineraryId, List.of("6"), UUID.randomUUID(), null);
        lifecycle.blockSeats(itineraryId, List.of("8"), "Daño",
                new Actor(UUID.randomUUID(), "OPERADOR", companyId));

        interno(post("/internal/itineraries/{id}/remap", itineraryId),
                json(Map.of("seatNumbers", List.of("1", "2", "3", "4", "9"), "capacidad", 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_CONFLICT"))
                .andExpect(jsonPath("$.error.details.asientos", contains("5", "6", "8")));

        assertThat(jdbc.queryForObject("select count(*) from seat_states where itinerary_id = ?", Long.class,
                itineraryId)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("select capacidad from itineraries where id = ?", Integer.class,
                itineraryId)).isEqualTo(8);

        interno(post("/internal/itineraries/{id}/remap", itineraryId),
                json(Map.of("seatNumbers", List.of("1", "2"), "capacidad", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CAPACITY"));
        interno(post("/internal/itineraries/{id}/remap", UUID.randomUUID()),
                json(Map.of("seatNumbers", List.of("1"), "capacidad", 1)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ resto de la API interna

    @Test
    void laApiInternaDeRetencionesYOfertasSigueIgual() throws Exception {
        UUID itineraryId = nuevoItinerario(6, null);
        String creada = interno(post("/internal/holds"), json(Map.of("itineraryId", itineraryId,
                "seatNumbers", List.of("1"), "userId", UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String holdId = objectMapper.readTree(creada).path("holdId").asText();

        mockMvc.perform(get("/internal/holds/{id}", holdId).header(InternalTokenFilter.HEADER, internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVA"));
        interno(post("/internal/holds/{id}/release", holdId), json(Map.of("motivo", "PRUEBA")))
                .andExpect(jsonPath("$.estado").value("LIBERADA"));

        UUID offerId = UUID.randomUUID();
        interno(post("/internal/offers/{id}/seats", offerId), json(Map.of("itineraryId", itineraryId,
                "seatNumbers", List.of("2", "3"))))
                .andExpect(jsonPath("$.reservados", contains("2", "3")));
        mockMvc.perform(post("/internal/offers/{id}/release", offerId)
                        .header(InternalTokenFilter.HEADER, internalToken))
                .andExpect(status().isNoContent());
        assertThat(estado(itineraryId, "2")).isEqualTo("DISPONIBLE");
    }

    // ------------------------------------------------------------------ eventos

    @Test
    void itineraryPublishedGuardaLaEmpresaEItineraryRescheduledCambiaLaFecha() {
        UUID itineraryId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        manejar("ItineraryPublished", Map.of("itineraryId", itineraryId, "companyId", companyId,
                "seatNumbers", List.of("1", "2"), "fechaSalida", "2026-12-01T10:00:00Z"));
        assertThat(jdbc.queryForObject("select company_id from itineraries where id = ?", UUID.class, itineraryId))
                .isEqualTo(companyId);

        manejar("ItineraryRescheduled", Map.of("itineraryId", itineraryId, "fechaSalida", "2026-12-03T08:30:00Z"));
        assertThat(jdbc.queryForObject("select fecha_salida from itineraries where id = ?", Timestamp.class,
                itineraryId).toInstant()).isEqualTo(Instant.parse("2026-12-03T08:30:00Z"));

        // Un itinerario que no conocemos se ignora, y sin fecha no se hace nada.
        manejar("ItineraryRescheduled", Map.of("itineraryId", UUID.randomUUID(),
                "fechaSalida", "2026-12-03T08:30:00Z"));
        manejar("ItineraryRescheduled", Map.of("itineraryId", itineraryId));
        assertThat(jdbc.queryForObject("select fecha_salida from itineraries where id = ?", Timestamp.class,
                itineraryId).toInstant()).isEqualTo(Instant.parse("2026-12-03T08:30:00Z"));
    }

    // ------------------------------------------------------------------ utilidades

    private UUID nuevoItinerario(int asientos, UUID companyId) {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, asientos).mapToObj(String::valueOf).toList(),
                null, companyId);
        return itineraryId;
    }

    private HoldResponse vender(UUID itineraryId, UUID bookingId, String... seats) {
        return vender(itineraryId, bookingId, UUID.randomUUID(), seats);
    }

    private HoldResponse vender(UUID itineraryId, UUID bookingId, UUID userId, String... seats) {
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of(seats), userId, null);
        return inventory.confirmSale(hold.holdId(), bookingId);
    }

    private ResultActions interno(MockHttpServletRequestBuilder request,
                                                                       String body) throws Exception {
        return mockMvc.perform(request.header(InternalTokenFilter.HEADER, internalToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions publico(MockHttpServletRequestBuilder request,
                                                                       RequestPostProcessor usuario, String body)
            throws Exception {
        return mockMvc.perform(request.with(usuario).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private String estado(UUID itineraryId, String seat) {
        return jdbc.queryForObject("select estado from seat_states where itinerary_id = ? and seat_number = ?",
                String.class, itineraryId, seat);
    }

    private Map<String, Object> fila(UUID itineraryId, String seat) {
        return jdbc.queryForMap("select estado, user_id, hold_id, vendido_en, bloqueo_motivo from seat_states "
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
