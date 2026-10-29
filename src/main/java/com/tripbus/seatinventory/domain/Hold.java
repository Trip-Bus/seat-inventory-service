package com.tripbus.seatinventory.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Retención temporal de uno o más asientos (US-022). */
@Entity
@Table(name = "holds")
public class Hold {

    /** Motivo con el que el pasajero libera su propia retención (US-191: cuenta como abandono). */
    public static final String LIBERADA_POR_USUARIO = "LIBERADA_POR_USUARIO";
    private static final int MAX_MOTIVO = 200;

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "hold_seats", joinColumns = @JoinColumn(name = "hold_id"))
    @Column(name = "seat_number", nullable = false)
    private List<String> seatNumbers = new ArrayList<>();

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(nullable = false)
    private boolean extendido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus estado;

    /** US-191: por qué se cerró (LIBERADA_POR_USUARIO, PAGO_RECHAZADO, EXPIRADA, ...). NULL mientras está activa. */
    @Column(name = "motivo_cierre")
    private String motivoCierre;

    @Version
    private long version;

    protected Hold() {
    }

    public Hold(UUID id, UUID itineraryId, UUID userId, UUID offerId, List<String> seatNumbers, Instant now, Duration ttl) {
        this.id = id;
        this.itineraryId = itineraryId;
        this.userId = userId;
        this.offerId = offerId;
        this.seatNumbers = new ArrayList<>(seatNumbers);
        this.creadoEn = now;
        this.expiraEn = now.plus(ttl);
        this.estado = HoldStatus.ACTIVA;
    }

    public boolean vencida(Instant now) {
        return !expiraEn.isAfter(now);
    }

    public void extender(Duration extension) {
        this.expiraEn = this.expiraEn.plus(extension);
        this.extendido = true;
    }

    public void confirmar(UUID bookingId) {
        this.estado = HoldStatus.CONFIRMADA;
        this.bookingId = bookingId;
    }

    public void cerrar(HoldStatus estadoFinal, String motivo) {
        this.estado = estadoFinal;
        this.motivoCierre = motivo != null && motivo.length() > MAX_MOTIVO ? motivo.substring(0, MAX_MOTIVO) : motivo;
    }

    /** US-151: el asiento anulado deja de ser parte de la compra (la auditoría conserva que lo fue). */
    public void quitarAsiento(String seatNumber) {
        this.seatNumbers.remove(seatNumber);
    }

    /** US-139: la compra cambia un asiento por otro del mismo viaje. */
    public void cambiarAsiento(String fromSeat, String toSeat) {
        this.seatNumbers.remove(fromSeat);
        this.seatNumbers.add(toSeat);
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

    public UUID getOfferId() {
        return offerId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }

    public boolean isExtendido() {
        return extendido;
    }

    public HoldStatus getEstado() {
        return estado;
    }

    public String getMotivoCierre() {
        return motivoCierre;
    }
}
