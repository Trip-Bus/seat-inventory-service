package com.tripbus.seatinventory.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.DomainEventHandler;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.service.SeatInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Eventos que consume Seat Inventory (doc: ItineraryPublished, ItineraryCancelled, PaymentFailed). */
@Component
public class InventoryEventsHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsHandler.class);

    private final SeatInventoryService inventoryService;

    public InventoryEventsHandler(SeatInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(InventoryEvents.ITINERARY_PUBLISHED, InventoryEvents.ITINERARY_CANCELLED,
                InventoryEvents.PAYMENT_FAILED, InventoryEvents.OFFER_CLOSED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case InventoryEvents.ITINERARY_PUBLISHED -> {
                List<String> seats = new ArrayList<>();
                p.path("seatNumbers").forEach(n -> seats.add(n.asText()));
                Instant salida = p.hasNonNull("fechaSalida") ? Instant.parse(p.get("fechaSalida").asText()) : null;
                if (!seats.isEmpty()) {
                    inventoryService.onItineraryPublished(uuid(p, "itineraryId"), seats, salida);
                }
            }
            case InventoryEvents.ITINERARY_CANCELLED ->
                    inventoryService.onItineraryCancelled(uuid(p, "itineraryId"), p.path("motivo").asText(""));
            case InventoryEvents.PAYMENT_FAILED -> {
                if (p.hasNonNull("holdId")) {
                    try {
                        inventoryService.releaseHold(uuid(p, "holdId"), null, "PAGO_RECHAZADO");
                    } catch (ApiException e) {
                        log.info("PaymentFailed: la retención {} no se liberó ({})", p.get("holdId").asText(),
                                e.getCode());
                    }
                }
            }
            case InventoryEvents.OFFER_CLOSED -> inventoryService.releaseOfferSeats(uuid(p, "offerId"));
            default -> {
            }
        }
    }

    private static UUID uuid(JsonNode p, String field) {
        return UUID.fromString(p.path(field).asText());
    }
}
