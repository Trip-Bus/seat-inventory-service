package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

/** anulados: los que cambiaron en esta llamada (vacío si se repite). asientos: estado actual de los pedidos. */
public record VoidSaleResponse(UUID itineraryId, UUID bookingId, String motivo, List<String> anulados,
                               List<SeatView> asientos) {
}
