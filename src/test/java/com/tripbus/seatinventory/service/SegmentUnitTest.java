package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.observability.InventoryMetrics;
import com.tripbus.seatinventory.common.redis.LeaderLock;
import com.tripbus.seatinventory.config.InventoryProperties;
import com.tripbus.seatinventory.domain.SeatSpec;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.SeatOccupancyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-142 y HT-08: ramas de SeatOccupancies, SeatAuditor e InventoryArchiver que no necesitan base de datos. */
class SegmentUnitTest {

    private static final Instant AHORA = Instant.parse("2026-11-05T12:00:00Z");

    private final SeatOccupancyRepository repository = mock(SeatOccupancyRepository.class);
    private final InventoryMetrics metrics = mock(InventoryMetrics.class);
    private final SeatOccupancies occupancies = new SeatOccupancies(repository, mock(HoldRepository.class), metrics);

    @Test
    void sinRetencionesDeLaReservaNoHayVentaQueBuscar() {
        SeatState seat = new SeatState(UUID.randomUUID(), UUID.randomUUID(), "1", AHORA);
        assertThat(occupancies.vendidaA(seat, List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void soloUnAsientoTramoSinOcupacionesVuelveADisponible() {
        SeatState disponible = new SeatState(UUID.randomUUID(), UUID.randomUUID(), "1", AHORA);
        assertThat(occupancies.liberarSiQuedoLibre(disponible, AHORA)).isFalse();

        SeatState conOtra = new SeatState(UUID.randomUUID(), UUID.randomUUID(), "2", AHORA);
        conOtra.ocuparTramo(AHORA);
        when(repository.existsByItineraryIdAndSeatNumber(conOtra.getItineraryId(), "2")).thenReturn(true);
        assertThat(occupancies.liberarSiQuedoLibre(conOtra, AHORA)).isFalse();
        assertThat(conOtra.getEstado()).isEqualTo(SeatStatus.TRAMO);

        SeatState libre = new SeatState(UUID.randomUUID(), UUID.randomUUID(), "3", AHORA);
        libre.ocuparTramo(AHORA);
        assertThat(occupancies.liberarSiQuedoLibre(libre, AHORA)).isTrue();
        assertThat(libre.getEstado()).isEqualTo(SeatStatus.DISPONIBLE);

        occupancies.dobleVentaBloqueada();
        verify(metrics).dobleVentaBloqueada();
    }

    @Test
    void retenerOVenderUnTramoNoLiberaNadaYSoltarloSi() {
        SeatState tramo = new SeatState(UUID.randomUUID(), UUID.randomUUID(), new SeatSpec("1", null), AHORA);
        tramo.ocuparTramo(AHORA);
        assertThat(SeatAuditor.liberaAsiento(tramo, SeatStatus.TRAMO, "HOLD")).isFalse();
        assertThat(SeatAuditor.liberaAsiento(tramo, SeatStatus.TRAMO, "SALE")).isFalse();
        assertThat(SeatAuditor.liberaAsiento(tramo, SeatStatus.TRAMO, "EXPIRADA")).isTrue();
        assertThat(SeatAuditor.liberaAsiento(tramo, SeatStatus.DISPONIBLE, "HOLD")).isFalse();
        SeatState disponible = new SeatState(UUID.randomUUID(), UUID.randomUUID(), "2", AHORA);
        assertThat(SeatAuditor.liberaAsiento(disponible, SeatStatus.RETENIDO, "LIBERADA")).isTrue();
        assertThat(SeatAuditor.liberaAsiento(disponible, SeatStatus.DISPONIBLE, "REMAP")).isFalse();
    }

    @Test
    void elArchivoCorreEnUnSoloNodoYSinLotesNoArchivaNada() {
        LeaderLock leaderLock = mock(LeaderLock.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        InventoryProperties properties = new InventoryProperties(null, null, 0, null, null, "http://x", 0);
        InventoryArchiver archiver = new InventoryArchiver(mock(JdbcTemplate.class), tx, leaderLock, properties,
                Clock.fixed(AHORA, ZoneOffset.UTC));

        archiver.archivar();
        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(leaderLock).runIfLeader(eq("seat-inventory-archive"), any(Duration.class), job.capture());
        // La transacción no devolvió nada (p. ej. se revirtió): no hay más lotes.
        job.getValue().run();
        assertThat(archiver.archivarViejos()).isZero();
        assertThat(properties.archiveAfterDays()).isEqualTo(90);
        verify(leaderLock).runIfLeader(anyString(), any(Duration.class), any(Runnable.class));
    }
}
