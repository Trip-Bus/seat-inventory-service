package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** US-119: asientos del bus nuevo del viaje. */
public record RemapSeatsRequest(@NotEmpty List<String> seatNumbers, @Positive int capacidad) {
}
