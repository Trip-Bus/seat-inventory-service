package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.HoldStatus;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.WaitlistEntry;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.KnownItineraryRepository;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.repository.WaitlistRepository;
import com.tripbus.seatinventory.web.dto.OccupancyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reglas puras de US-191 y US-140 que no necesitan base de datos. */
class OccupancyAndWaitlistUnitTest {

    private final SeatStateRepository seats = mock(SeatStateRepository.class);
    private final HoldRepository holds = mock(HoldRepository.class);
    private final KnownItineraryRepository itineraries = mock(KnownItineraryRepository.class);
    private final OccupancyService occupancy = new OccupancyService(seats, holds, itineraries);

    @Test
    void elInternoRechazaMasDeDoscientosViajesYAceptaLaListaVacia() {
        List<UUID> demasiados = IntStream.range(0, OccupancyService.MAX_ITINERARIOS + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> occupancy.ocupaciones(demasiados))
                .extracting("code").isEqualTo("TOO_MANY_ITINERARIES");
        assertThat(occupancy.ocupaciones(List.of())).isEmpty();
        // Repetidos cuentan una vez.
        UUID id = UUID.randomUUID();
        List<UUID> repetidos = IntStream.range(0, 300).mapToObj(i -> id).toList();
        assertThat(occupancy.ocupaciones(repetidos)).isEmpty();
    }

    @Test
    void unViajeSinAsientosNiRetencionesNoDivideEntreCero() {
        UUID id = UUID.randomUUID();
        when(itineraries.findAllById(anyCollection())).thenReturn(List.of(new KnownItinerary(id, 0, null, null)));
        when(seats.countByEstado(anyCollection())).thenReturn(List.of());
        when(holds.countByEstado(anyCollection(), any())).thenReturn(List.of());

        OccupancyResponse r = occupancy.ocupaciones(List.of(id)).get(0);
        assertThat(r.porcentajeOcupacion()).isZero();
        assertThat(r.tasaAbandono()).isZero();
        assertThat(r.retenciones().creadas()).isZero();
    }

    @Test
    void redondeaPorcentajesYTasas() {
        assertThat(OccupancyService.redondear(100.0 / 3, 2)).isEqualTo(33.33);
        assertThat(OccupancyService.redondear(2.0 / 3, 4)).isEqualTo(0.6667);
    }

    @Test
    void elTurnoQuedaSegunComoTerminaLaRetencion() {
        assertThat(WaitlistStatus.trasRetencion(HoldStatus.CONFIRMADA)).isEqualTo(WaitlistStatus.CONVERTIDO);
        assertThat(WaitlistStatus.trasRetencion(HoldStatus.CANCELADA)).isEqualTo(WaitlistStatus.CANCELADO);
        assertThat(WaitlistStatus.trasRetencion(HoldStatus.EXPIRADA)).isEqualTo(WaitlistStatus.VENCIDO);
        assertThat(WaitlistStatus.trasRetencion(HoldStatus.LIBERADA)).isEqualTo(WaitlistStatus.VENCIDO);
    }

    @Test
    void laRetencionGuardaElMotivoDeCierreRecortado() {
        Hold hold = new Hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("1"),
                Instant.now(), Duration.ofMinutes(10));
        assertThat(hold.getMotivoCierre()).isNull();
        hold.cerrar(HoldStatus.CANCELADA, "ITINERARIO_CANCELADO: " + "x".repeat(300));
        assertThat(hold.getMotivoCierre()).hasSize(200).startsWith("ITINERARIO_CANCELADO");
        hold.cerrar(HoldStatus.LIBERADA, null);
        assertThat(hold.getMotivoCierre()).isNull();
    }

    @Test
    void dosAnotacionesSimultaneasDelMismoPasajeroLasDecideElIndiceUnico() {
        WaitlistRepository waitlist = mock(WaitlistRepository.class);
        SeatInventoryService inventory = mock(SeatInventoryService.class);
        UUID itineraryId = UUID.randomUUID();
        when(inventory.ensureItinerary(itineraryId)).thenReturn(new KnownItinerary(itineraryId, 1, null, null));
        when(seats.countByItineraryIdAndEstado(itineraryId, SeatStatus.DISPONIBLE)).thenReturn(0L);
        when(waitlist.saveAndFlush(any(WaitlistEntry.class))).thenThrow(new DataIntegrityViolationException("uq"));
        WaitlistService service = new WaitlistService(waitlist, seats, inventory, Clock.fixed(Instant.now(),
                ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.anotarse(itineraryId, userId, 1))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ALREADY_IN_WAITLIST");
    }
}
