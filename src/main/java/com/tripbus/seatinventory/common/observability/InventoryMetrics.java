package com.tripbus.seatinventory.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * HT-10: métricas propias del inventario (en Prometheus, ver {@link MetricsConfig}):
 * <ul>
 *   <li>{@code tripbus_retenciones_total{resultado=creada|confirmada|expirada|liberada}}</li>
 *   <li>{@code tripbus_lock_fallido_total}: no se obtuvieron los locks de Redis de los asientos a tiempo</li>
 *   <li>{@code tripbus_doble_venta_bloqueada_total}: la base de datos rechazó una doble venta (trigger,
 *       único por asiento o la restricción EXCLUDE de los tramos), es decir, la última línea de defensa actuó</li>
 *   <li>{@code tripbus_retencion_segundos}: Timer de HoldSeats (locks + transacción), también si falla</li>
 * </ul>
 */
@Component
public class InventoryMetrics {

    static final String RETENCIONES = "tripbus.retenciones";
    static final String LOCK_FALLIDO = "tripbus.lock.fallido";
    static final String DOBLE_VENTA_BLOQUEADA = "tripbus.doble.venta.bloqueada";
    static final String RETENCION = "tripbus.retencion";

    /** Resultado de una retención, la etiqueta {@code resultado}. */
    public enum Resultado {
        CREADA, CONFIRMADA, EXPIRADA, LIBERADA;

        String etiqueta() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter lockFallido;
    private final Counter dobleVentaBloqueada;
    private final Timer retencion;

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.lockFallido = Counter.builder(LOCK_FALLIDO)
                .description("Intentos que no obtuvieron a tiempo los locks de Redis de sus asientos")
                .register(registry);
        this.dobleVentaBloqueada = Counter.builder(DOBLE_VENTA_BLOQUEADA)
                .description("Dobles ventas que rechazó la base de datos (última línea de defensa)")
                .register(registry);
        this.retencion = Timer.builder(RETENCION)
                .description("Tiempo de retener asientos (HoldSeats)")
                .register(registry);
    }

    public void retencion(Resultado resultado) {
        Counter.builder(RETENCIONES).description("Retenciones de asientos por resultado")
                .tag("resultado", resultado.etiqueta())
                .register(registry).increment();
    }

    public void lockFallido() {
        lockFallido.increment();
    }

    public void dobleVentaBloqueada() {
        dobleVentaBloqueada.increment();
    }

    /** Mide una retención (también si falla). */
    public <T> T medirRetencion(Supplier<T> tarea) {
        return retencion.record(tarea);
    }
}
