package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UnblockSeatsRequest(@NotEmpty(message = "Debe indicar al menos un asiento") List<String> seatNumbers) {
}
