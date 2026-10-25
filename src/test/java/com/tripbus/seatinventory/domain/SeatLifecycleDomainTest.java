package com.tripbus.seatinventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLifecycleDomainTest {

    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private final UUID itineraryId = UUID.randomUUID();

    private SeatState vendido(UUID holdId, UUID userId) {
        SeatState seat = new SeatState(UUID.randomUUID(), itineraryId, "4", now);
        seat.retener(holdId, userId, now);
        seat.vender(now);
        return seat;
    }

    @Test
    void unAsientoNuevoNaceDisponible() {
        SeatState seat = new SeatState(UUID.randomUUID(), itineraryId, "9", now);
        assertThat(seat.getEstado()).isEqualTo(SeatStatus.DISPONIBLE);
        assertThat(seat.getItineraryId()).isEqualTo(itineraryId);
        assertThat(seat.getSeatNumber()).isEqualTo("9");
        assertThat(seat.vendidoA(Set.of(UUID.randomUUID()))).isFalse();
    }

    @Test
    void anularLimpiaComprador() {
        UUID holdId = UUID.randomUUID();
        SeatState seat = vendido(holdId, UUID.randomUUID());
        assertThat(seat.vendidoA(Set.of(holdId))).isTrue();
        assertThat(seat.vendidoA(Set.of(UUID.randomUUID()))).isFalse();

        seat.anular(now);

        assertThat(seat.getEstado()).isEqualTo(SeatStatus.DISPONIBLE);
        assertThat(seat.getUserId()).isNull();
        assertThat(seat.getHoldId()).isNull();
        assertThat(seat.getOfferId()).isNull();
        assertThat(seat.getVendidoEn()).isNull();
        assertThat(seat.vendidoA(Set.of(holdId))).isFalse();
    }

    @Test
    void venderEnCambioDeConservaCompradorRetencionYFecha() {
        UUID holdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SeatState anterior = vendido(holdId, userId);
        SeatState nuevo = new SeatState(UUID.randomUUID(), itineraryId, "7", now);

        nuevo.venderEnCambioDe(anterior, now.plusSeconds(60));

        assertThat(nuevo.getEstado()).isEqualTo(SeatStatus.VENDIDO);
        assertThat(nuevo.getUserId()).isEqualTo(userId);
        assertThat(nuevo.getHoldId()).isEqualTo(holdId);
        assertThat(nuevo.getVendidoEn()).isEqualTo(anterior.getVendidoEn());
    }

    @Test
    void bloquearYDesbloquear() {
        SeatState seat = new SeatState(UUID.randomUUID(), itineraryId, "1", now);
        seat.bloquear("Daño", now);
        assertThat(seat.getEstado()).isEqualTo(SeatStatus.BLOQUEADO);
        assertThat(seat.getBloqueoMotivo()).isEqualTo("Daño");
        assertThat(seat.disponiblePara(null)).isFalse();

        seat.desbloquear(now);
        assertThat(seat.getEstado()).isEqualTo(SeatStatus.DISPONIBLE);
        assertThat(seat.getBloqueoMotivo()).isNull();
    }

    @Test
    void laRetencionCambiaYQuitaAsientos() {
        Hold hold = new Hold(UUID.randomUUID(), itineraryId, UUID.randomUUID(), null, List.of("1", "2"), now,
                Duration.ofMinutes(10));
        hold.cambiarAsiento("1", "5");
        assertThat(hold.getSeatNumbers()).containsExactlyInAnyOrder("2", "5");
        hold.quitarAsiento("2");
        assertThat(hold.getSeatNumbers()).containsExactly("5");
    }

    @Test
    void elItinerarioSeReprogramaYCambiaDeCapacidad() {
        UUID companyId = UUID.randomUUID();
        KnownItinerary itinerary = new KnownItinerary(UUID.randomUUID(), 40, now, companyId);
        itinerary.reprogramar(now.plusSeconds(3600));
        itinerary.cambiarCapacidad(44);
        assertThat(itinerary.getFechaSalida()).isEqualTo(now.plusSeconds(3600));
        assertThat(itinerary.getCapacidad()).isEqualTo(44);
        assertThat(itinerary.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void elOrigenDeUnaAnulacionLlevaElMotivo() {
        assertThat(VoidReason.REPROGRAMACION.origen()).isEqualTo("VOID_REPROGRAMACION");
        assertThat(VoidReason.CAMBIO_BUS.origen()).isEqualTo("VOID_CAMBIO_BUS");
    }
}
