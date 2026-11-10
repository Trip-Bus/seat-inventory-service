package com.tripbus.seatinventory.domain;

/** US-151: por qué booking devuelve al inventario un asiento vendido (origen VOID_&lt;motivo&gt; en la auditoría). */
public enum VoidReason {
    REPROGRAMACION,
    CANCELACION_EMPRESA,
    REEMBOLSO,
    CAMBIO_BUS;

    public String origen() {
        return "VOID_" + name();
    }
}
