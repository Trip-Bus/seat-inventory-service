package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

public record SwapSeatResponse(UUID itineraryId, UUID bookingId, UUID holdId, String fromSeat, String toSeat,
                               List<SeatView> asientos) {
}
