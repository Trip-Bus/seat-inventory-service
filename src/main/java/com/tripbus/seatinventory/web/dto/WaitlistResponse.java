package com.tripbus.seatinventory.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-140: un turno de la lista de espera. posicion (desde 1) solo viene mientras está ESPERANDO. */
public record WaitlistResponse(
        UUID id,
        UUID itineraryId,
        int cantidad,
        String estado,
        Long posicion,
        UUID holdId,
        Instant creadoEn,
        Instant actualizadoEn) {
}
