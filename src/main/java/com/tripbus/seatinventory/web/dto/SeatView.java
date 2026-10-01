package com.tripbus.seatinventory.web.dto;

import java.util.UUID;

/** Estado público de un asiento: nunca expone el usuario que lo retiene/compró. */
public record SeatView(String seatNumber, String estado, UUID offerId, boolean propio) {
}
