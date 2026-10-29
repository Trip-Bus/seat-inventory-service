package com.tripbus.seatinventory.web.dto;

import java.util.UUID;

/**
 * US-191: ocupación y abandono de compra de un viaje. porcentajeOcupacion = vendidos / capacidad (en %, con dos
 * decimales); tasaAbandono = (expiradas + liberadasPorUsuario) / creadas (de 0 a 1, con cuatro decimales).
 */
public record OccupancyResponse(
        UUID itineraryId,
        int capacidad,
        long disponibles,
        long retenidos,
        long vendidos,
        long enOferta,
        long bloqueados,
        double porcentajeOcupacion,
        Retenciones retenciones,
        double tasaAbandono) {

    /** liberadas incluye liberadasPorUsuario; las demás liberadas fueron del sistema (p. ej. pago rechazado). */
    public record Retenciones(
            long creadas,
            long activas,
            long confirmadas,
            long expiradas,
            long liberadas,
            long liberadasPorUsuario,
            long canceladas) {
    }
}
