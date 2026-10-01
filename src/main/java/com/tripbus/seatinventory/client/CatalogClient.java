package com.tripbus.seatinventory.client;

import com.tripbus.seatinventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Valida el itinerario y obtiene sus asientos desde Catalog (inicialización perezosa). */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(InventoryProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.restClient = RestClient.builder().baseUrl(properties.catalogUrl()).requestFactory(factory).build();
    }

    public record SeatMap(UUID itineraryId, int capacidad, List<String> asientos) {
    }

    public Optional<SeatMap> seatMap(UUID itineraryId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/itineraries/{id}/seatmap", itineraryId)
                    .retrieve()
                    .body(SeatMap.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Catalog no respondió el mapa de asientos de {}: {}", itineraryId, e.getMessage());
            return Optional.empty();
        }
    }
}
