package com.tripbus.seatinventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Reglas duras del doc: retención de 10 min, extensión única de máx. 5 min,
 * lock de Redis con TTL 15 s y espera máxima de 500 ms para adquirir todos los locks.
 */
@ConfigurationProperties(prefix = "tripbus.inventory")
public record InventoryProperties(
        Duration holdTtl,
        Duration holdExtension,
        int maxSeatsPerHold,
        Duration lockTtl,
        Duration lockWait,
        String catalogUrl) {

    public InventoryProperties {
        holdTtl = holdTtl != null ? holdTtl : Duration.ofMinutes(10);
        holdExtension = holdExtension != null ? holdExtension : Duration.ofMinutes(5);
        if (holdExtension.compareTo(Duration.ofMinutes(5)) > 0) {
            holdExtension = Duration.ofMinutes(5);
        }
        maxSeatsPerHold = maxSeatsPerHold > 0 ? maxSeatsPerHold : 6;
        lockTtl = lockTtl != null ? lockTtl : Duration.ofSeconds(15);
        lockWait = lockWait != null ? lockWait : Duration.ofMillis(500);
    }
}
