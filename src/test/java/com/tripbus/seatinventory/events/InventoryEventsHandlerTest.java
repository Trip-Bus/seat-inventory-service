package com.tripbus.seatinventory.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.EventEnvelope;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
import com.tripbus.seatinventory.service.WaitlistProcessor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InventoryEventsHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SeatInventoryService inventory = mock(SeatInventoryService.class);
    private final SeatLifecycleService lifecycle = mock(SeatLifecycleService.class);
    private final WaitlistProcessor waitlist = mock(WaitlistProcessor.class);
    private final InventoryEventsHandler handler = new InventoryEventsHandler(inventory, lifecycle, waitlist);
    private final UUID itineraryId = UUID.randomUUID();

    @Test
    void declaraLosEventosQueConsume() {
        assertThat(handler.eventTypes()).containsExactlyInAnyOrder("ItineraryPublished", "ItineraryCancelled",
                "PaymentFailed", "OfferClosed", "ItineraryRescheduled");
    }

    @Test
    void itineraryPublishedPasaLaEmpresaYLaFecha() {
        UUID companyId = UUID.randomUUID();
        handle("ItineraryPublished", Map.of("itineraryId", itineraryId, "companyId", companyId,
                "seatNumbers", List.of("1", "2"), "fechaSalida", "2026-12-01T10:00:00Z"));
        verify(inventory).onItineraryPublished(itineraryId, List.of("1", "2"),
                Instant.parse("2026-12-01T10:00:00Z"), companyId);
    }

    @Test
    void itineraryPublishedSinEmpresaNiAsientos() {
        handle("ItineraryPublished", Map.of("itineraryId", itineraryId, "seatNumbers", List.of("1")));
        verify(inventory).onItineraryPublished(eq(itineraryId), eq(List.of("1")), isNull(), isNull());

        SeatInventoryService otro = mock(SeatInventoryService.class);
        new InventoryEventsHandler(otro, lifecycle, waitlist).handle(envelope("ItineraryPublished",
                Map.of("itineraryId", itineraryId)));
        verifyNoInteractions(otro);
    }

    @Test
    void itineraryRescheduledActualizaLaFechaSoloSiViene() {
        handle("ItineraryRescheduled", Map.of("itineraryId", itineraryId, "fechaSalida", "2026-12-03T08:30:00Z"));
        verify(lifecycle).onItineraryRescheduled(itineraryId, Instant.parse("2026-12-03T08:30:00Z"));

        SeatLifecycleService otro = mock(SeatLifecycleService.class);
        new InventoryEventsHandler(inventory, otro, waitlist).handle(envelope("ItineraryRescheduled",
                Map.of("itineraryId", itineraryId)));
        verifyNoInteractions(otro);
    }

    @Test
    void cancelacionPagoRechazadoYOfertaCerrada() {
        UUID holdId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        handle("ItineraryCancelled", Map.of("itineraryId", itineraryId, "motivo", "LLUVIA"));
        handle("PaymentFailed", Map.of("holdId", holdId));
        handle("PaymentFailed", Map.of("bookingId", UUID.randomUUID()));
        handle("OfferClosed", Map.of("offerId", offerId));
        handle("OtroEvento", Map.of());

        verify(waitlist).onItineraryCancelled(itineraryId);
        verify(inventory).onItineraryCancelled(itineraryId, "LLUVIA");
        verify(inventory).releaseHold(holdId, null, "PAGO_RECHAZADO");
        verify(inventory).releaseOfferSeats(offerId);
    }

    @Test
    void unPagoRechazadoDeUnaRetencionYaConfirmadaNoFalla() {
        UUID holdId = UUID.randomUUID();
        doThrow(ApiException.conflict("HOLD_ALREADY_CONFIRMED", "ya confirmada"))
                .when(inventory).releaseHold(any(), any(), anyString());
        handle("PaymentFailed", Map.of("holdId", holdId));
        verify(inventory).releaseHold(holdId, null, "PAGO_RECHAZADO");
    }

    private void handle(String type, Map<String, Object> payload) {
        handler.handle(envelope(type, payload));
    }

    private EventEnvelope envelope(String type, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), type, "test", Instant.now(),
                objectMapper.valueToTree(payload));
    }
}
