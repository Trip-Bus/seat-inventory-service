package com.tripbus.seatinventory.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        long segundosRestantes) {
}
