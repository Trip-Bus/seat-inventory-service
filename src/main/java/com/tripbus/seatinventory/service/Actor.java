package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.domain.KnownItinerary;

import java.util.UUID;

/**
 * Quién hace la petición pública (claims del JWT de identity). US-112: solo el OPERADOR de la empresa
 * dueña del viaje, o un ADMIN, gestiona sus asientos bloqueados. Un itinerario sin empresa (anterior a V4)
 * solo lo gestiona el ADMIN.
 */
public record Actor(UUID userId, String role, UUID companyId) {

    public static final String ADMIN = "ADMIN";
    public static final String OPERADOR = "OPERADOR";

    public boolean puedeGestionar(KnownItinerary itinerary) {
        if (ADMIN.equals(role)) {
            return true;
        }
        return OPERADOR.equals(role) && companyId != null && companyId.equals(itinerary.getCompanyId());
    }
}
