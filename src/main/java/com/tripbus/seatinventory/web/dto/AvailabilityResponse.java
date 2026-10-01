package com.tripbus.seatinventory.web.dto;

import java.util.UUID;

/** disponibles incluye los asientos EN_OFERTA (también se pueden comprar, a precio de oferta). */
public record AvailabilityResponse(UUID itineraryId, long disponibles, long enOferta, long total) {
}
