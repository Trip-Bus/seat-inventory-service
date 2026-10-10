package com.tripbus.seatinventory;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.service.ExpiredHoldsSweeper;
import com.tripbus.seatinventory.service.InventoryInitializer;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
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

/**
 * US-080: "Probar automáticamente que ningún asiento se vende dos veces".
 * 500 hilos compiten por el mismo asiento; exactamente uno lo obtiene. Se repite
 * CONCURRENCY_RUNS veces (el pipeline de CI usa 100 como gate). Corre contra PostgreSQL 16 y
 * Redis 7 reales levantados por Testcontainers (requiere Docker): Flyway aplica las migraciones
 * REALES de db/migration (incluido el trigger V2 contra la doble venta) sobre una base nueva.
 */
@SpringBootTest
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class SeatConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final int THREADS = Integer.parseInt(System.getenv().getOrDefault("CONCURRENCY_THREADS", "500"));
    private static final int RUNS = Integer.parseInt(System.getenv().getOrDefault("CONCURRENCY_RUNS", "5"));

    @Autowired
    private SeatInventoryService inventory;
    @Autowired
    private InventoryInitializer initializer;
    @Autowired
    private ExpiredHoldsSweeper sweeper;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID nuevoItinerario() {
        UUID itineraryId = UUID.randomUUID();
        initializer.initialize(itineraryId, IntStream.rangeClosed(1, 40).mapToObj(String::valueOf).toList(), null);
        return itineraryId;
    }

    @Test
    void soloUnPasajeroRetieneUnAsientoDisputadoEnCadaEjecucion() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int run = 1; run <= RUNS; run++) {
                UUID itineraryId = nuevoItinerario();
                CountDownLatch salida = new CountDownLatch(1);
                AtomicInteger exitos = new AtomicInteger();
                AtomicInteger conflictos = new AtomicInteger();
                ConcurrentLinkedQueue<Throwable> inesperados = new ConcurrentLinkedQueue<>();
                List<Future<?>> futuros = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    futuros.add(pool.submit(() -> {
                        try {
                            salida.await();
                            inventory.holdSeats(itineraryId, List.of("1"), UUID.randomUUID(), null);
                            exitos.incrementAndGet();
                        } catch (ApiException e) {
                            conflictos.incrementAndGet();
                        } catch (Throwable e) {
                            inesperados.add(e);
                        }
                        return null;
                    }));
                }
                salida.countDown();
                for (Future<?> f : futuros) {
                    f.get(60, TimeUnit.SECONDS);
                }
                assertThat(inesperados).as("errores inesperados en la ejecución %d", run).isEmpty();
                assertThat(exitos.get()).as("retenciones exitosas en la ejecución %d", run).isEqualTo(1);
                assertThat(conflictos.get()).isEqualTo(THREADS - 1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from holds where itinerary_id = ? and estado = 'ACTIVA'", Long.class,
                        itineraryId)).isEqualTo(1L);
                assertThat(jdbc.queryForObject(
                        "select estado from seat_states where itinerary_id = ? and seat_number = '1'", String.class,
                        itineraryId)).isEqualTo("RETENIDO");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dosConfirmacionesConcurrentesDeLaMismaRetencionVendenUnaSolaVez() throws Exception {
        UUID itineraryId = nuevoItinerario();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("5"), UUID.randomUUID(), null);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger exitos = new AtomicInteger();
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> futuros = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    inventory.confirmSale(hold.holdId(), UUID.randomUUID());
                    exitos.incrementAndGet();
                } catch (ApiException ignored) {
                    // otra compra ya usó la retención
                }
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> f : futuros) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(exitos.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select estado from seat_states where itinerary_id = ? and seat_number = '5'", String.class,
                itineraryId)).isEqualTo("VENDIDO");
    }

    @Test
    void laBaseDeDatosImpideVenderDosVecesAunqueLaAplicacionFalle() {
        UUID itineraryId = nuevoItinerario();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("7"), UUID.randomUUID(), null);
        inventory.confirmSale(hold.holdId(), UUID.randomUUID());

        // Intento "por fuera" de la aplicación de revender el asiento a otro usuario.
        assertThatThrownBy(() -> jdbc.update(
                "update seat_states set hold_id = ?, user_id = ? where itinerary_id = ? and seat_number = '7'",
                UUID.randomUUID(), UUID.randomUUID(), itineraryId))
                .hasMessageContaining("DOUBLE_SALE");
        // Y tampoco puede existir dos veces el mismo asiento.
        assertThatThrownBy(() -> jdbc.update(
                "insert into seat_states (id, itinerary_id, seat_number, estado, version, actualizado_en) "
                        + "values (?, ?, '7', 'DISPONIBLE', 0, now())", UUID.randomUUID(), itineraryId))
                .hasMessageContaining("uq_seat_states_itinerary_seat");
    }

    @Test
    void unAsientoOcupadoNoSePuedeSeleccionar() {
        UUID itineraryId = nuevoItinerario();
        inventory.holdSeats(itineraryId, List.of("3"), UUID.randomUUID(), null);
        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, List.of("3", "4"), UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        // El asiento 4 no quedó retenido a medias.
        assertThat(jdbc.queryForObject(
                "select estado from seat_states where itinerary_id = ? and seat_number = '4'", String.class,
                itineraryId)).isEqualTo("DISPONIBLE");
    }

    @Test
    void laRetencionSeExtiendeUnaSolaVezYExpiraConElBarrido() {
        UUID itineraryId = nuevoItinerario();
        UUID user = UUID.randomUUID();
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("9"), user, null);
        assertThat(hold.segundosRestantes()).isBetween(590L, 600L);

        HoldResponse extendida = inventory.extendHold(hold.holdId(), user);
        assertThat(extendida.expiraEn()).isAfter(hold.expiraEn());
        assertThatThrownBy(() -> inventory.extendHold(hold.holdId(), user))
                .extracting("code").isEqualTo("HOLD_ALREADY_EXTENDED");

        jdbc.update("update holds set expira_en = now() - interval '1 second' where id = ?", hold.holdId());
        assertThat(sweeper.releaseExpiredHolds()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select estado from seat_states where itinerary_id = ? and seat_number = '9'", String.class,
                itineraryId)).isEqualTo("DISPONIBLE");
        assertThatThrownBy(() -> inventory.confirmSale(hold.holdId(), UUID.randomUUID()))
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
    }

    @Test
    void losAsientosEnOfertaSoloSeRetienenPorLaOfertaYVuelvenAlCerrarla() {
        UUID itineraryId = nuevoItinerario();
        UUID offerId = UUID.randomUUID();
        var reserva = inventory.reserveOfferSeats(offerId, itineraryId, List.of("20", "21"));
        assertThat(reserva.reservados()).containsExactly("20", "21");

        assertThatThrownBy(() -> inventory.holdSeats(itineraryId, List.of("20"), UUID.randomUUID(), null))
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
        HoldResponse hold = inventory.holdSeats(itineraryId, List.of("20"), UUID.randomUUID(), offerId);
        inventory.releaseHold(hold.holdId(), null, "PRUEBA");
        assertThat(jdbc.queryForObject(
                "select estado from seat_states where itinerary_id = ? and seat_number = '20'", String.class,
                itineraryId)).isEqualTo("EN_OFERTA");

        inventory.releaseOfferSeats(offerId);
        assertThat(jdbc.queryForList(
                "select estado from seat_states where itinerary_id = ? and seat_number in ('20','21')", String.class,
                itineraryId)).containsOnly("DISPONIBLE");
    }
}
