package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-139: la reserva cambia fromSeat por toSeat en el mismo viaje. */
public record SwapSeatRequest(
        @NotNull UUID itineraryId,
        @NotNull UUID bookingId,
        @NotBlank String fromSeat,
        @NotBlank String toSeat) {
}
