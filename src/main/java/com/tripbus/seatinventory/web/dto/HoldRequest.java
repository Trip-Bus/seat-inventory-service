package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record HoldRequest(
        @NotNull(message = "El itinerario es obligatorio")
        UUID itineraryId,

        @NotEmpty(message = "Debe indicar al menos un asiento")
        List<String> seatNumbers) {
}
