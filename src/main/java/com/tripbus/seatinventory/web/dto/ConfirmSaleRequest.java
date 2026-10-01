package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotNull;

public record ConfirmSaleRequest(@NotNull(message = "bookingId es obligatorio") java.util.UUID bookingId) {
}
