package com.tripbus.seatinventory.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.DomainEventHandler;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
import com.tripbus.seatinventory.service.WaitlistProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Eventos que consume Seat Inventory (doc: ItineraryPublished, ItineraryCancelled, PaymentFailed; además
 * OfferClosed e ItineraryRescheduled).
 */
@Component
public class InventoryEventsHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsHandler.class);
    private static final String ITINERARY_ID = "itineraryId";
    private static final String FECHA_SALIDA = "fechaSalida";

    private final SeatInventoryService inventoryService;
    private final SeatLifecycleService lifecycleService;
    private final WaitlistProcessor waitlistProcessor;

    public InventoryEventsHandler(SeatInventoryService inventoryService, SeatLifecycleService lifecycleService,
                                  WaitlistProcessor waitlistProcessor) {
        this.inventoryService = inventoryService;
        this.lifecycleService = lifecycleService;
        this.waitlistProcessor = waitlistProcessor;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(InventoryEvents.ITINERARY_PUBLISHED, InventoryEvents.ITINERARY_CANCELLED,
                InventoryEvents.PAYMENT_FAILED, InventoryEvents.OFFER_CLOSED, InventoryEvents.ITINERARY_RESCHEDULED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case InventoryEvents.ITINERARY_PUBLISHED -> {
                List<String> seats = new ArrayList<>();
                p.path("seatNumbers").forEach(n -> seats.add(n.asText()));
                UUID companyId = p.hasNonNull("companyId") ? uuid(p, "companyId") : null;
                if (!seats.isEmpty()) {
                    inventoryService.onItineraryPublished(uuid(p, ITINERARY_ID), seats, instant(p, FECHA_SALIDA),
                            companyId);
                }
            }
            case InventoryEvents.ITINERARY_CANCELLED -> {
                UUID itineraryId = uuid(p, ITINERARY_ID);
                // US-140: primero se cierra la fila (WaitlistClosed lleva también a quien ya tenía una oferta).
                waitlistProcessor.onItineraryCancelled(itineraryId);
                inventoryService.onItineraryCancelled(itineraryId, p.path("motivo").asText(""));
            }
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
            case InventoryEvents.ITINERARY_RESCHEDULED -> {
                Instant salida = instant(p, FECHA_SALIDA);
                if (salida != null) {
                    lifecycleService.onItineraryRescheduled(uuid(p, ITINERARY_ID), salida);
                }
            }
            default -> {
            }
        }
    }

    private static UUID uuid(JsonNode p, String field) {
        return UUID.fromString(p.path(field).asText());
    }

    private static Instant instant(JsonNode p, String field) {
        return p.hasNonNull(field) ? Instant.parse(p.get(field).asText()) : null;
    }
}
