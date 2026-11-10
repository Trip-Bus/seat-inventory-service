package com.tripbus.seatinventory.web.dto;

import com.tripbus.seatinventory.domain.VoidReason;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** US-151: booking devuelve al inventario asientos que vendió. */
public record VoidSaleRequest(
        @NotNull UUID itineraryId,
        @NotNull UUID bookingId,
        @NotEmpty List<String> seatNumbers,
        @NotNull VoidReason motivo) {
}
