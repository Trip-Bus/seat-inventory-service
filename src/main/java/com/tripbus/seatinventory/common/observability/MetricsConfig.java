package com.tripbus.seatinventory.common.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HT-10: los Timer propios ({@code tripbus.*}) salen en Prometheus en segundos con el sufijo en español
 * ({@code tripbus_retencion_segundos}) en vez de {@code _seconds}; el resto usa la convención de
 * siempre.
 */
@Configuration
public class MetricsConfig {

    static final String PREFIJO = "tripbus.";

    @Bean
    public MeterRegistryCustomizer<PrometheusMeterRegistry> nombresEnEspanol() {
        return registry -> registry.config().namingConvention(
                new TimersEnSegundos(registry.config().namingConvention()));
    }

    /** Convención de nombres que solo cambia el sufijo de los Timer propios. */
    record TimersEnSegundos(NamingConvention delegada) implements NamingConvention {

        @Override
        public String name(String name, Meter.Type type, String baseUnit) {
            if (type == Meter.Type.TIMER && name.startsWith(PREFIJO)) {
                return NamingConvention.snakeCase.name(name, type, baseUnit) + "_segundos";
            }
            return delegada.name(name, type, baseUnit);
        }

        @Override
        public String tagKey(String key) {
            return delegada.tagKey(key);
        }
    }
}
