package com.tripbus.seatinventory.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HT-10: traceparent hacia y desde los mensajes, con el puente real de OpenTelemetry. */
class TracePropagationTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
    private final io.opentelemetry.api.trace.Tracer otel = provider.get("prueba");
    private final Tracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {
    });
    private final TracePropagation tracing = new TracePropagation(tracer,
            new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel));

    @AfterEach
    void cerrar() {
        provider.close();
        MDC.clear();
    }

    @Test
    void sinTrazaEnCursoNoHayTraceparent() {
        assertThat(tracing.actual()).isNull();
    }

    @Test
    void elConsumidorSigueLaTrazaDeQuienEncolo() {
        Span productor = tracer.nextSpan().name("productor").start();
        String traceparent;
        try (Tracer.SpanInScope ignored = tracer.withSpan(productor)) {
            traceparent = tracing.actual();
        } finally {
            productor.end();
        }
        assertThat(traceparent).startsWith("00-" + productor.context().traceId() + "-");

        AtomicReference<String> trazaDelConsumidor = new AtomicReference<>();
        AtomicReference<String> itinerarioEnMdc = new AtomicReference<>();
        String itineraryId = UUID.randomUUID().toString();
        tracing.enSpan("consumidor", traceparent, itineraryId, () -> {
            trazaDelConsumidor.set(tracer.currentSpan().context().traceId());
            itinerarioEnMdc.set(MDC.get(TracePropagation.MDC_ITINERARY));
        });

        assertThat(trazaDelConsumidor.get()).isEqualTo(productor.context().traceId());
        assertThat(itinerarioEnMdc.get()).isEqualTo(itineraryId);
        assertThat(MDC.get(TracePropagation.MDC_ITINERARY)).isNull();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void sinTraceparentEmpiezaUnaTrazaNuevaYNoPoneItinerariosInvalidos() {
        AtomicReference<Span> span = new AtomicReference<>();
        AtomicReference<String> itinerarioEnMdc = new AtomicReference<>("x");
        tracing.enSpan("consumidor", null, "no-es-un-uuid\ninyectado", () -> {
            span.set(tracer.currentSpan());
            itinerarioEnMdc.set(MDC.get(TracePropagation.MDC_ITINERARY));
        });

        assertThat(span.get()).isNotNull();
        assertThat(itinerarioEnMdc.get()).isNull();
    }

    @Test
    void lasExcepcionesSePropaganYSeLimpiaElMdc() {
        String itineraryId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tracing.enSpan("consumidor", null, itineraryId, () -> {
            throw new IllegalStateException("falla");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TracePropagation.MDC_ITINERARY)).isNull();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void sinTracingUsaNoopYLaTareaCorreIgual() {
        TracePropagation sinTracing = new TracePropagation(new DefaultListableBeanFactory().getBeanProvider(Tracer.class),
                new DefaultListableBeanFactory().getBeanProvider(Propagator.class));
        AtomicReference<Boolean> corrio = new AtomicReference<>(false);

        sinTracing.enSpan("consumidor", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null,
                () -> corrio.set(true));

        assertThat(corrio.get()).isTrue();
        assertThat(sinTracing.actual()).isNull();
    }
}
