package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

public record OfferSeatsResponse(UUID offerId, UUID itineraryId, List<String> reservados, List<String> omitidos) {
}
