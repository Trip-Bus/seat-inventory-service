package com.tripbus.seatinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * US-142: un asiento ocupado (RETENIDO o VENDIDO) en un tramo por una retención. Toda retención y venta, completa
 * o por tramo, tiene una por asiento; la restricción EXCLUDE de la tabla impide que dos del mismo asiento se crucen.
 */
@Entity
@Table(name = "seat_occupancies")
public class SeatOccupancy {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "desde_orden", nullable = false)
    private int desdeOrden;

    @Column(name = "hasta_orden", nullable = false)
    private int hastaOrden;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus estado;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected SeatOccupancy() {
    }

    /** Ocupación RETENIDO de un asiento de la retención, en su tramo. */
    public SeatOccupancy(Hold hold, String seatNumber, Instant now) {
        this.id = UUID.randomUUID();
        this.itineraryId = hold.getItineraryId();
        this.seatNumber = seatNumber;
        this.desdeOrden = hold.getTramo().desde();
        this.hastaOrden = hold.getTramo().hasta();
        this.estado = SeatStatus.RETENIDO;
        this.holdId = hold.getId();
        this.userId = hold.getUserId();
        this.creadoEn = now;
    }

    public void vender() {
        this.estado = SeatStatus.VENDIDO;
    }

    /** US-139: la venta pasa a otro asiento del mismo viaje, en el mismo tramo. */
    public void moverA(String otroAsiento) {
        this.seatNumber = otroAsiento;
    }

    public Tramo getTramo() {
        return new Tramo(desdeOrden, hastaOrden);
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getEstado() {
        return estado;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public UUID getUserId() {
        return userId;
    }
}
