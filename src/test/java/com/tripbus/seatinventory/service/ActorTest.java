package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.domain.KnownItinerary;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActorTest {

    private final UUID empresa = UUID.randomUUID();
    private final KnownItinerary deLaEmpresa = new KnownItinerary(UUID.randomUUID(), 40, null, empresa);
    private final KnownItinerary sinEmpresa = new KnownItinerary(UUID.randomUUID(), 40, null, null);

    @Test
    void elAdminGestionaCualquierViaje() {
        Actor admin = new Actor(UUID.randomUUID(), Actor.ADMIN, null);
        assertThat(admin.puedeGestionar(deLaEmpresa)).isTrue();
        assertThat(admin.puedeGestionar(sinEmpresa)).isTrue();
    }

    @Test
    void elOperadorSoloGestionaLosViajesDeSuEmpresa() {
        assertThat(new Actor(UUID.randomUUID(), Actor.OPERADOR, empresa).puedeGestionar(deLaEmpresa)).isTrue();
        assertThat(new Actor(UUID.randomUUID(), Actor.OPERADOR, UUID.randomUUID()).puedeGestionar(deLaEmpresa))
                .isFalse();
        assertThat(new Actor(UUID.randomUUID(), Actor.OPERADOR, null).puedeGestionar(deLaEmpresa)).isFalse();
        // Un itinerario anterior a V4 (sin empresa) solo lo gestiona el ADMIN.
        assertThat(new Actor(UUID.randomUUID(), Actor.OPERADOR, empresa).puedeGestionar(sinEmpresa)).isFalse();
    }

    @Test
    void otrosRolesNoGestionan() {
        assertThat(new Actor(UUID.randomUUID(), "PASAJERO", empresa).puedeGestionar(deLaEmpresa)).isFalse();
        assertThat(new Actor(UUID.randomUUID(), null, empresa).puedeGestionar(deLaEmpresa)).isFalse();
    }
}
