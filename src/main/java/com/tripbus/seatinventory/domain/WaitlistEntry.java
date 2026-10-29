package com.tripbus.seatinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Turno de un pasajero en la lista de espera de un viaje agotado (US-140). */
@Entity
@Table(name = "waitlist")
public class WaitlistEntry {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int cantidad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus estado;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected WaitlistEntry() {
    }

    public WaitlistEntry(UUID id, UUID itineraryId, UUID userId, int cantidad, Instant now) {
        this.id = id;
        this.itineraryId = itineraryId;
        this.userId = userId;
        this.cantidad = cantidad;
        this.estado = WaitlistStatus.ESPERANDO;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    /** Se le creó una retención con los asientos que esperaba. */
    public void ofrecer(UUID holdId, Instant now) {
        this.estado = WaitlistStatus.OFRECIDO;
        this.holdId = holdId;
        this.actualizadoEn = now;
    }

    /** El pasajero salió de la fila o la fila se cerró. */
    public void cancelar(Instant now) {
        this.estado = WaitlistStatus.CANCELADO;
        this.actualizadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getCantidad() {
        return cantidad;
    }

    public WaitlistStatus getEstado() {
        return estado;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
