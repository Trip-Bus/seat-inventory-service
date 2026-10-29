package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.events.Outbox;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.SeatAuditLog;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.events.InventoryEvents;
import com.tripbus.seatinventory.repository.SeatAuditLogRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registra cada cambio de estado de un asiento en seat_audit_log y lo anuncia (SeatStateChanged) vía
 * outbox, en la misma transacción del cambio. Requiere transacción (el outbox es MANDATORY). Cuando un asiento
 * vuelve a DISPONIBLE publica {@link AsientoLiberado} (US-140).
 */
@Component
public class SeatAuditor {

    private final SeatAuditLogRepository auditRepository;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

    public SeatAuditor(SeatAuditLogRepository auditRepository, Outbox outbox, ApplicationEventPublisher events) {
        this.auditRepository = auditRepository;
        this.outbox = outbox;
        this.events = events;
    }

    /** Audita con el usuario y la retención que el asiento tiene después del cambio. */
    public void registrar(SeatState seat, SeatStatus anterior, String origen, Instant now) {
        registrar(seat, anterior, origen, now, seat.getUserId(), seat.getHoldId());
    }

    /**
     * Audita con un usuario/retención explícitos: en una anulación el asiento queda sin dueño, pero la
     * auditoría conserva a quién se le anuló (y de eso depende la idempotencia de US-151).
     */
    public void registrar(SeatState seat, SeatStatus anterior, String origen, Instant now, UUID userId,
                          UUID holdId) {
        auditRepository.save(new SeatAuditLog(seat.getItineraryId(), seat.getSeatNumber(), anterior,
                seat.getEstado(), userId, holdId, origen, now));
        Map<String, Object> payload = new HashMap<>();
        payload.put("itineraryId", seat.getItineraryId());
        payload.put("seatNumber", seat.getSeatNumber());
        payload.put("estadoAnterior", anterior != null ? anterior.name() : null);
        payload.put("estadoNuevo", seat.getEstado().name());
        payload.put("offerId", seat.getOfferId());
        outbox.add(InventoryEvents.SEAT_STATE_CHANGED, payload);
        if (seat.getEstado() == SeatStatus.DISPONIBLE && anterior != SeatStatus.DISPONIBLE) {
            // US-140: un asiento volvió a la venta; la lista de espera del viaje se procesa tras el commit.
            events.publishEvent(new AsientoLiberado(seat.getItineraryId()));
        }
    }

    /**
     * ¿Alguna de estas retenciones ya dejó el asiento con este origen (patrón LIKE)? Lo que hace
     * idempotentes la anulación y el cambio de asiento: el asiento ya no tiene dueño, la auditoría sí.
     */
    public boolean yaLiberadoPor(SeatState seat, Collection<UUID> holdIds, String origen) {
        return !holdIds.isEmpty()
                && auditRepository.yaLiberadoPor(seat.getItineraryId(), seat.getSeatNumber(), holdIds, origen);
    }

    /** SeatReleased de asientos vendidos que vuelven al inventario (anulación o cambio de asiento). */
    public void anunciarLiberados(Hold hold, List<String> seats, UUID bookingId, String motivo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", hold.getItineraryId());
        payload.put("seatNumbers", seats);
        payload.put("holdId", hold.getId());
        payload.put("offerId", hold.getOfferId());
        payload.put("bookingId", bookingId);
        payload.put("motivo", motivo);
        outbox.add(InventoryEvents.SEAT_RELEASED, payload);
    }
}
