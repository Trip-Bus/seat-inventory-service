package com.tripbus.seatinventory.domain;

/** US-017: estados distinguibles de cada asiento. BLOQUEADO (US-112): la empresa no lo vende. */
public enum SeatStatus {
    DISPONIBLE,
    RETENIDO,
    VENDIDO,
    EN_OFERTA,
    BLOQUEADO
}
