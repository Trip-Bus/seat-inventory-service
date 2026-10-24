package com.tripbus.seatinventory.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Estado público de un asiento: nunca expone el usuario que lo retiene/compró. motivoBloqueo (US-112)
 * solo viene para el operador de la empresa del viaje o el ADMIN; para los demás se omite.
 */
public record SeatView(String seatNumber, String estado, UUID offerId, boolean propio,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String motivoBloqueo) {
}
