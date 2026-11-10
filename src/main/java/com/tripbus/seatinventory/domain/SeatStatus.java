package com.tripbus.seatinventory.domain;

/**
 * US-017: estados distinguibles de cada asiento. BLOQUEADO (US-112): la empresa no lo vende. TRAMO (US-142): está
 * retenido o vendido solo en parte del recorrido (seat_occupancies dice en qué tramos); no está libre para el
 * recorrido completo, pero sí para los tramos que no se cruzan con esas ocupaciones.
 */
public enum SeatStatus {
    DISPONIBLE,
    RETENIDO,
    VENDIDO,
    EN_OFERTA,
    BLOQUEADO,
    TRAMO
}
