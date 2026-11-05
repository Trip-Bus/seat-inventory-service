package com.tripbus.seatinventory.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HT-10: traduce las variables de observabilidad a las propiedades de Spring Boot antes de que arranquen
 * el logging y la autoconfiguración:
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} (URL base del colector, p. ej. {@code http://otel:4318}):
 *   con valor, las trazas se exportan por OTLP/HTTP a {@code <endpoint>/v1/traces}; vacía, no se exportan
 *   (Boot crearía el exportador con solo definir la propiedad, aunque esté vacía).</li>
 *   <li>{@code tripbus.log-format} ({@code LOG_FORMAT}): con {@code json}, logs estructurados ECS
 *   ({@code logging.structured.format.console=ecs}).</li>
 * </ul>
 * Lo que se haya configurado explícitamente gana (la fuente se agrega con la menor precedencia). Se
 * registra en {@code META-INF/spring.factories} y corre después de cargar los application-*.yml.
 */
public class ObservabilityEnvironment implements EnvironmentPostProcessor {

    static final String FUENTE = "tripbus-observabilidad";
    static final String OTLP_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String RUTA_TRAZAS = "/v1/traces";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> propiedades = new HashMap<>();
        String endpoint = environment.getProperty(OTLP_ENDPOINT);
        if (StringUtils.hasText(endpoint)) {
            propiedades.put("management.otlp.tracing.endpoint", trazas(endpoint.trim()));
        } else {
            propiedades.put("management.otlp.tracing.export.enabled", "false");
        }
        String formato = environment.getProperty("tripbus.log-format", "text");
        if ("json".equals(formato.trim().toLowerCase(Locale.ROOT))) {
            propiedades.put("logging.structured.format.console", "ecs");
        }
        environment.getPropertySources().addLast(new MapPropertySource(FUENTE, propiedades));
    }

    /** URL de las trazas: la base del colector + /v1/traces (si no viene ya con la ruta). */
    static String trazas(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base.endsWith(RUTA_TRAZAS) ? base : base + RUTA_TRAZAS;
    }
}
