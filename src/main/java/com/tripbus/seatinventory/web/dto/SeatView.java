package com.tripbus.seatinventory.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Estado público de un asiento: nunca expone el usuario que lo retiene/compró. motivoBloqueo (US-112)
 * solo viene para el operador de la empresa del viaje o el ADMIN; para los demás se omite. categoria (US-141):
 * ESTANDAR si catalog no mandó otra; el recargo lo calcula catalog.
 */
public record SeatView(String seatNumber, String estado, String categoria, UUID offerId, boolean propio,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String motivoBloqueo) {
}
