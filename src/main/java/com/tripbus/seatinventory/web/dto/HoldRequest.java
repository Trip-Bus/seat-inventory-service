package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** fromOrden/toOrden (US-142): tramo [fromOrden, toOrden) por el orden de las paradas; sin ellos, todo el recorrido. */
public record HoldRequest(
        @NotNull(message = "El itinerario es obligatorio")
        UUID itineraryId,

        @NotEmpty(message = "Debe indicar al menos un asiento")
        List<String> seatNumbers,

        Integer fromOrden,

        Integer toOrden) {
}
