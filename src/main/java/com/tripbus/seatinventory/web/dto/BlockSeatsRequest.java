package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** US-112: asientos que la empresa no vende y por qué. */
public record BlockSeatsRequest(
        @NotEmpty(message = "Debe indicar al menos un asiento")
        List<String> seatNumbers,

        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 200, message = "El motivo admite hasta 200 caracteres")
        String motivo) {
}
