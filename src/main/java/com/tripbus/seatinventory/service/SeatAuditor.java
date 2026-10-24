package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.events.Outbox;
import com.tripbus.seatinventory.domain.SeatAuditLog;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.events.InventoryEvents;
import com.tripbus.seatinventory.repository.SeatAuditLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Registra cada cambio de estado de un asiento en seat_audit_log y lo anuncia (SeatStateChanged) vía
 * outbox, en la misma transacción del cambio. Requiere transacción (el outbox es MANDATORY).
 */
@Component
public class SeatAuditor {

    private final SeatAuditLogRepository auditRepository;
    private final Outbox outbox;

    public SeatAuditor(SeatAuditLogRepository auditRepository, Outbox outbox) {
        this.auditRepository = auditRepository;
        this.outbox = outbox;
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
    }
}
