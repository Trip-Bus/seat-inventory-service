package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

public record RemapSeatsResponse(UUID itineraryId, int capacidad, List<String> agregados, List<String> eliminados) {
}
