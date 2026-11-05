package com.tripbus.seatinventory.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * US-141: un asiento del bus con su categoría (ItineraryPublished.seats[] y el cambio de bus). categoria null
 * significa "no vino": un asiento nuevo queda ESTANDAR y uno que ya existía conserva la suya. El recargo de cada
 * categoría lo calcula catalog, no este servicio.
 */
public record SeatSpec(String numero, String categoria) {

    public static final String ESTANDAR = "ESTANDAR";
    private static final Pattern VALIDA = Pattern.compile("[A-Z0-9_]{1,30}");

    public SeatSpec {
        numero = numero == null ? "" : numero.trim();
        categoria = normalizar(categoria);
    }

    public static SeatSpec sinCategoria(String numero) {
        return new SeatSpec(numero, null);
    }

    /** En mayúsculas; vacía o con caracteres fuera de [A-Z0-9_] (o más de 30), como si no viniera. */
    static String normalizar(String categoria) {
        if (categoria == null) {
            return null;
        }
        String c = categoria.trim().toUpperCase(Locale.ROOT);
        return VALIDA.matcher(c).matches() ? c : null;
    }

    public String categoriaONueva() {
        return categoria != null ? categoria : ESTANDAR;
    }
}
