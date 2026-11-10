package com.tripbus.seatinventory.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityEnvironmentTest {

    private final ObservabilityEnvironment processor = new ObservabilityEnvironment();

    private MockEnvironment procesar(MockEnvironment env) {
        processor.postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void sinEndpointNoSeExportanTrazasYLosLogsSonTexto() {
        MockEnvironment env = procesar(new MockEnvironment());

        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isNull();
        assertThat(env.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
        assertThat(env.getProperty("logging.structured.format.console")).isNull();
    }

    @Test
    void endpointVacioEsComoNoTenerlo() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "  "));

        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isNull();
    }

    @Test
    void conEndpointLasTrazasVanAlaRutaDeTrazas() {
        MockEnvironment env = procesar(new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318/"));

        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isEqualTo("http://otel:4318/v1/traces");
        assertThat(env.getProperty("management.otlp.tracing.export.enabled")).isNull();
    }

    @Test
    void siElEndpointYaTraeLaRutaNoSeRepite() {
        assertThat(ObservabilityEnvironment.trazas("http://otel:4318/v1/traces"))
                .isEqualTo("http://otel:4318/v1/traces");
        assertThat(ObservabilityEnvironment.trazas("http://otel:4318")).isEqualTo("http://otel:4318/v1/traces");
    }

    @Test
    void logFormatJsonActivaLosLogsEcs() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("tripbus.log-format", " JSON "));

        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }

    @Test
    void loConfiguradoExplicitamenteGana() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("tripbus.log-format", "json")
                .withProperty("logging.structured.format.console", "logstash"));

        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("logstash");
    }
}
