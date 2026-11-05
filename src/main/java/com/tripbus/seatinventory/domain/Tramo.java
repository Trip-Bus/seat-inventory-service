package com.tripbus.seatinventory.domain;

import com.tripbus.seatinventory.common.error.ApiException;

/**
 * US-142: tramo [desde, hasta) del recorrido, por el orden de las paradas: se sube en la parada desde y se baja en
 * la parada hasta. Dos tramos se cruzan si comparten algún trayecto entre paradas; [1, 3) y [3, 5) no se cruzan.
 * El recorrido completo es [1, 32767).
 */
public record Tramo(int desde, int hasta) {

    public static final int PRIMERA_PARADA = 1;
    public static final int FIN = 32767;
    public static final Tramo COMPLETO = new Tramo(PRIMERA_PARADA, FIN);

    public Tramo {
        if (desde < PRIMERA_PARADA || desde >= hasta || hasta > FIN) {
            throw ApiException.badRequest("INVALID_SEGMENT",
                    "El tramo debe cumplir 1 <= fromOrden < toOrden <= " + FIN);
        }
    }

    /** Sin from ni to, el recorrido completo; con uno solo, 400 INVALID_SEGMENT. */
    public static Tramo de(Integer fromOrden, Integer toOrden) {
        if (fromOrden == null && toOrden == null) {
            return COMPLETO;
        }
        if (fromOrden == null || toOrden == null) {
            throw ApiException.badRequest("INVALID_SEGMENT", "Indica fromOrden y toOrden, o ninguno de los dos");
        }
        return new Tramo(fromOrden, toOrden);
    }

    public boolean esCompleto() {
        return equals(COMPLETO);
    }

    public boolean cruza(Tramo otro) {
        return desde < otro.hasta && otro.desde < hasta;
    }
}
