package com.tripbus.seatinventory.web.dto;

import java.util.List;
import java.util.UUID;

public record SeatStatesResponse(UUID itineraryId, int capacidad, Resumen resumen, List<SeatView> asientos) {

    public record Resumen(long disponibles, long retenidos, long vendidos, long enOferta, long bloqueados) {
    }
}
