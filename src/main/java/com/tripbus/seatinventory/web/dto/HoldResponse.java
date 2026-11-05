package com.tripbus.seatinventory.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** fromOrden/toOrden (US-142) solo vienen en una retención por tramo; sin ellos es del recorrido completo. */
public record HoldResponse(
        UUID holdId,
        UUID itineraryId,
        List<String> seatNumbers,
        UUID userId,
        UUID offerId,
        UUID bookingId,
        String estado,
        Instant creadoEn,
        Instant expiraEn,
        boolean extendido,
        long segundosRestantes,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer fromOrden,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer toOrden) {
}
