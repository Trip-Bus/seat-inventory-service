package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.Outbox;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.WaitlistEntry;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import com.tripbus.seatinventory.events.InventoryEvents;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaitlistProcessorTest {

    private static final Instant AHORA = Instant.parse("2026-11-01T12:00:00Z");

    private final WaitlistRepository waitlist = mock(WaitlistRepository.class);
    private final SeatStateRepository seats = mock(SeatStateRepository.class);
    private final SeatInventoryService inventory = mock(SeatInventoryService.class);
    private final Outbox outbox = mock(Outbox.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final WaitlistProcessor processor = new WaitlistProcessor(waitlist, seats, inventory, outbox, tx,
            Clock.fixed(AHORA, ZoneOffset.UTC));
    private final UUID itineraryId = UUID.randomUUID();

    @BeforeEach
    void enEspera() {
        when(waitlist.existsByItineraryIdAndEstado(itineraryId, WaitlistStatus.ESPERANDO)).thenReturn(true);
    }

    @Test
    void sinTurnosEsperandoNoAbreTransaccion() {
        when(waitlist.existsByItineraryIdAndEstado(itineraryId, WaitlistStatus.ESPERANDO)).thenReturn(false);
        assertThat(processor.procesar(itineraryId)).isZero();
        verify(tx, never()).execute(any());
    }

    @Test
    void siOtroTomaElAsientoReintentaYSeRindeTrasTresIntentos() {
        when(tx.execute(any())).thenThrow(ApiException.conflict("SEAT_LOCKED", "ocupado"));
        assertThat(processor.procesar(itineraryId)).isZero();
        verify(tx, times(WaitlistProcessor.MAX_INTENTOS)).execute(any());
    }

    @Test
    void ofreceMientrasHayaAQuien() {
        when(tx.execute(any())).thenReturn(true, true, false);
        assertThat(processor.procesar(itineraryId)).isEqualTo(2);
    }

    @Test
    void conElViajeCanceladoCierraTodaLaFila() {
        enTransaccion();
        KnownItinerary itinerary = new KnownItinerary(itineraryId, 2, null, null);
        itinerary.cancelar();
        when(inventory.ensureItinerary(itineraryId)).thenReturn(itinerary);
        WaitlistEntry ana = new WaitlistEntry(UUID.randomUUID(), itineraryId, UUID.randomUUID(), 1, AHORA);
        when(waitlist.lockFila(itineraryId, WaitlistStatus.ACTIVOS)).thenReturn(List.of(ana));

        assertThat(processor.procesar(itineraryId)).isZero();
        assertThat(ana.getEstado()).isEqualTo(WaitlistStatus.CANCELADO);
        verify(waitlist).advisoryLock(eq(WaitlistProcessor.NAMESPACE), anyInt());
        verify(outbox).add(eq(InventoryEvents.WAITLIST_CLOSED), any(Map.class));
        verify(inventory, never()).holdSeats(any(), anyList(), any(), any());
    }

    @Test
    void aMenosDeMediaHoraDeLaSalidaSoloCierraLosQueEsperan() {
        enTransaccion();
        when(inventory.ensureItinerary(itineraryId))
                .thenReturn(new KnownItinerary(itineraryId, 2, AHORA.plus(Duration.ofMinutes(30)), null));
        when(waitlist.lockFila(itineraryId, List.of(WaitlistStatus.ESPERANDO))).thenReturn(List.of());

        assertThat(processor.procesar(itineraryId)).isZero();
        // Nadie esperaba: no hay aviso que publicar.
        verify(outbox, never()).add(any(), any());
    }

    @Test
    void siNingunTurnoCabeEnLoLibreNoOfreceNada() {
        enTransaccion();
        when(inventory.ensureItinerary(itineraryId)).thenReturn(new KnownItinerary(itineraryId, 4, null, null));
        when(seats.findSeatNumbers(itineraryId, SeatStatus.DISPONIBLE)).thenReturn(List.of("4"));
        when(waitlist.lockFila(itineraryId, List.of(WaitlistStatus.ESPERANDO))).thenReturn(
                List.of(new WaitlistEntry(UUID.randomUUID(), itineraryId, UUID.randomUUID(), 2, AHORA)));

        assertThat(processor.procesar(itineraryId)).isZero();
        verify(inventory, never()).holdSeats(any(), anyList(), any(), any());
    }

    @Test
    void laFilaSeCierraMediaHoraAntesDeLaSalida() {
        Instant salida = AHORA.plus(Duration.ofHours(1));
        assertThat(WaitlistProcessor.cerradaPorSalida(new KnownItinerary(itineraryId, 1, null, null), AHORA))
                .isFalse();
        assertThat(WaitlistProcessor.cerradaPorSalida(new KnownItinerary(itineraryId, 1, salida, null),
                salida.minus(Duration.ofMinutes(31)))).isFalse();
        assertThat(WaitlistProcessor.cerradaPorSalida(new KnownItinerary(itineraryId, 1, salida, null),
                salida.minus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void elBarridoProcesaLosViajesQueDebenCerrarse() {
        when(waitlist.findItineraryIdsACerrar(AHORA.plus(WaitlistProcessor.CIERRE_ANTES_DE_SALIDA)))
                .thenReturn(List.of(itineraryId));
        when(tx.execute(any())).thenReturn(false);
        assertThat(processor.cerrarProximasASalir()).isEqualTo(1);
        verify(tx).execute(any());
    }

    @SuppressWarnings("unchecked")
    private void enTransaccion() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
                .doInTransaction(null));
    }
}
