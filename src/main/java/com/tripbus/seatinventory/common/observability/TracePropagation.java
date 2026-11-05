package com.tripbus.seatinventory.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HT-10: lleva el contexto de la traza (W3C {@code traceparent}) por los eventos de Redis Streams: el outbox
 * guarda {@link #actual()} junto a cada evento, el relay lo publica en el stream y quien lo
 * consume corre su trabajo con {@link #enSpan}, como hijo de esa traza. Sin tracing (pruebas) usa el
 * Tracer y el Propagator NOOP: no guarda nada y la tarea corre igual.
 *
 * <p>También pone el itineraryId en el MDC mientras corre la tarea, para que salga en cada línea de log.
 */
@Component
public class TracePropagation {

    /** Campo del mensaje (y columna del outbox) con el contexto de la traza. */
    public static final String CAMPO = "traceparent";
    /** Clave del MDC con el itinerario del que trata la línea de log. */
    public static final String MDC_ITINERARY = "itineraryId";

    private static final Pattern UUID_VALIDO =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public TracePropagation(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    TracePropagation(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** El traceparent del span actual, o null si no hay traza en curso. */
    public String actual() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(CAMPO);
    }

    /**
     * Corre la tarea en un span nuevo, hijo del traceparent recibido (o raíz si no vino), con el itineraryId
     * en el MDC (si viene y es un UUID). Las excepciones de la tarea se marcan en el span y se propagan.
     */
    public void enSpan(String nombre, String traceparent, String itineraryId, Runnable tarea) {
        Span span = (traceparent == null ? tracer.spanBuilder()
                : propagator.extract(Map.of(CAMPO, traceparent), Map::get))
                .name(nombre)
                .kind(Span.Kind.CONSUMER)
                .start();
        boolean conItinerario = itineraryId != null && UUID_VALIDO.matcher(itineraryId).matches();
        if (conItinerario) {
            MDC.put(MDC_ITINERARY, itineraryId);
        }
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            tarea.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
            if (conItinerario) {
                MDC.remove(MDC_ITINERARY);
            }
        }
    }
}
