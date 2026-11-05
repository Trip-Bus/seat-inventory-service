package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

public record SeatStatesResponse(UUID itineraryId, int capacidad, Resumen resumen, List<SeatView> asientos) {

    /** enTramos (US-142): ocupados solo en parte del recorrido; en una consulta por tramo siempre es 0. */
    public record Resumen(long disponibles, long retenidos, long vendidos, long enOferta, long bloqueados,
                          long enTramos) {
    }
}
