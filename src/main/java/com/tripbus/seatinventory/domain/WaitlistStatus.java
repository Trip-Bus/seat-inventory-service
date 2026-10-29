package com.tripbus.seatinventory.domain;

import java.util.List;

/** Estado de un turno de la lista de espera (US-140). */
public enum WaitlistStatus {
    ESPERANDO,
    OFRECIDO,
    CONVERTIDO,
    VENCIDO,
    CANCELADO;

    /** Turnos que siguen en la fila: un pasajero tiene a lo sumo uno por viaje. */
    public static final List<WaitlistStatus> ACTIVOS = List.of(ESPERANDO, OFRECIDO);

    /**
     * Cómo queda un turno OFRECIDO cuando se cierra la retención que se le creó: si se vendió, CONVERTIDO;
     * si el viaje se canceló, CANCELADO; si venció o se liberó sin pagar, VENCIDO.
     */
    public static WaitlistStatus trasRetencion(HoldStatus estadoRetencion) {
        return switch (estadoRetencion) {
            case CONFIRMADA -> CONVERTIDO;
            case CANCELADA -> CANCELADO;
            default -> VENCIDO;
        };
    }
}
