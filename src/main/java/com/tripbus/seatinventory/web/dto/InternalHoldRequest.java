package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** HoldSeats(itineraryId, seatIds[], userId, ttl) para otros servicios (p. ej. Flash Offers). */
public record InternalHoldRequest(
        @NotNull UUID itineraryId,
        @NotEmpty List<String> seatNumbers,
        @NotNull UUID userId,
        UUID offerId) {
}
