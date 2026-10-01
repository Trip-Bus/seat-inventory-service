package com.tripbus.seatinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_audit_log")
public class SeatAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_anterior")
    private SeatStatus estadoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_nuevo", nullable = false)
    private SeatStatus estadoNuevo;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(nullable = false)
    private String origen;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected SeatAuditLog() {
    }

    public SeatAuditLog(UUID itineraryId, String seatNumber, SeatStatus estadoAnterior, SeatStatus estadoNuevo,
                        UUID userId, UUID holdId, String origen, Instant creadoEn) {
        this.itineraryId = itineraryId;
        this.seatNumber = seatNumber;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.userId = userId;
        this.holdId = holdId;
        this.origen = origen;
        this.creadoEn = creadoEn;
    }
}
