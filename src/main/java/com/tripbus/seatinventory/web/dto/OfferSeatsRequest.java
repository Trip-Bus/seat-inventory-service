package com.tripbus.seatinventory.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record OfferSeatsRequest(@NotNull UUID itineraryId, @NotEmpty List<String> seatNumbers) {
}
