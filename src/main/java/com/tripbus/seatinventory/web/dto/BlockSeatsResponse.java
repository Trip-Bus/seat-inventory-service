package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

/** cambiados: los que pasaron a BLOQUEADO (o a DISPONIBLE al desbloquear); sinCambio: ya estaban así. */
public record BlockSeatsResponse(UUID itineraryId, List<String> cambiados, List<String> sinCambio) {
}
