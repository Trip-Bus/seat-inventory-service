package com.tripbus.seatinventory.common.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: nombres y etiquetas de las métricas propias tal como salen en Prometheus. */
class InventoryMetricsTest {

    private PrometheusMeterRegistry registry;
    private InventoryMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().nombresEnEspanol().customize(registry);
        metrics = new InventoryMetrics(registry);
    }

    @Test
    void retencionesPorResultado() {
        metrics.retencion(InventoryMetrics.Resultado.CREADA);
        metrics.retencion(InventoryMetrics.Resultado.CREADA);
        metrics.retencion(InventoryMetrics.Resultado.CONFIRMADA);
        metrics.retencion(InventoryMetrics.Resultado.EXPIRADA);
        metrics.retencion(InventoryMetrics.Resultado.LIBERADA);

        assertThat(registry.scrape())
                .contains("tripbus_retenciones_total{resultado=\"creada\"} 2.0")
                .contains("tripbus_retenciones_total{resultado=\"confirmada\"} 1.0")
                .contains("tripbus_retenciones_total{resultado=\"expirada\"} 1.0")
                .contains("tripbus_retenciones_total{resultado=\"liberada\"} 1.0");
    }

    @Test
    void locksFallidosYDoblesVentasBloqueadas() {
        metrics.lockFallido();
        metrics.dobleVentaBloqueada();
        metrics.dobleVentaBloqueada();

        assertThat(registry.scrape())
                .contains("tripbus_lock_fallido_total 1.0")
                .contains("tripbus_doble_venta_bloqueada_total 2.0");
    }

    @Test
    void elTimerDeRetencionSaleEnSegundos() {
        assertThat(metrics.medirRetencion(() -> "ok")).isEqualTo("ok");

        assertThat(registry.scrape()).contains("tripbus_retencion_segundos_count 1")
                .contains("tripbus_retencion_segundos_sum")
                .doesNotContain("tripbus_retencion_seconds");
    }

    @Test
    void losDemasTimerSiguenConLaConvencionDeSiempre() {
        registry.timer("http.server.requests").record(() -> {
        });

        assertThat(registry.scrape()).contains("http_server_requests_seconds_count 1");
    }
}
