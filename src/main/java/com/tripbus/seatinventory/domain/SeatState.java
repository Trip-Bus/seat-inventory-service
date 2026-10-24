package com.tripbus.seatinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@Entity
@Table(name = "seat_states")
public class SeatState {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus estado;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "vendido_en")
    private Instant vendidoEn;

    @Column(name = "bloqueo_motivo")
    private String bloqueoMotivo;

    @Version
    private long version;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected SeatState() {
    }

    /** Asiento nuevo, DISPONIBLE (US-119: llega con el bus nuevo). */
    public SeatState(UUID id, UUID itineraryId, String seatNumber, Instant now) {
        this.id = id;
        this.itineraryId = itineraryId;
        this.seatNumber = seatNumber;
        this.estado = SeatStatus.DISPONIBLE;
        this.actualizadoEn = now;
    }

    /** ¿Lo compró la reserva que tiene alguna de estas retenciones? */
    public boolean vendidoA(Collection<UUID> holdIds) {
        return estado == SeatStatus.VENDIDO && holdId != null && holdIds.contains(holdId);
    }

    /** US-151: la venta se anula y el asiento vuelve a la venta normal, sin comprador ni oferta. */
    public void anular(Instant now) {
        this.estado = SeatStatus.DISPONIBLE;
        this.userId = null;
        this.holdId = null;
        this.offerId = null;
        this.vendidoEn = null;
        this.actualizadoEn = now;
    }

    /** US-139: queda vendido al mismo comprador y con la misma retención que el asiento que deja. */
    public void venderEnCambioDe(SeatState anterior, Instant now) {
        this.estado = SeatStatus.VENDIDO;
        this.userId = anterior.userId;
        this.holdId = anterior.holdId;
        this.vendidoEn = anterior.vendidoEn;
        this.actualizadoEn = now;
    }

    /** US-112: la empresa no lo vende. */
    public void bloquear(String motivo, Instant now) {
        this.estado = SeatStatus.BLOQUEADO;
        this.bloqueoMotivo = motivo;
        this.actualizadoEn = now;
    }

    public void desbloquear(Instant now) {
        this.estado = SeatStatus.DISPONIBLE;
        this.bloqueoMotivo = null;
        this.actualizadoEn = now;
    }

    public boolean disponiblePara(UUID offerIdSolicitada) {
        if (offerIdSolicitada == null) {
            return estado == SeatStatus.DISPONIBLE;
        }
        return estado == SeatStatus.EN_OFERTA && offerIdSolicitada.equals(offerId);
    }

    public void retener(UUID holdId, UUID userId, Instant now) {
        this.estado = SeatStatus.RETENIDO;
        this.holdId = holdId;
        this.userId = userId;
        this.actualizadoEn = now;
    }

    /** Vuelve a estar libre: si sigue perteneciendo a una oferta vigente vuelve a EN_OFERTA. */
    public void liberar(Instant now) {
        this.estado = offerId != null ? SeatStatus.EN_OFERTA : SeatStatus.DISPONIBLE;
        this.holdId = null;
        this.userId = null;
        this.actualizadoEn = now;
    }

    public void vender(Instant now) {
        this.estado = SeatStatus.VENDIDO;
        this.vendidoEn = now;
        this.actualizadoEn = now;
    }

    public void ponerEnOferta(UUID offerId, Instant now) {
        this.estado = SeatStatus.EN_OFERTA;
        this.offerId = offerId;
        this.actualizadoEn = now;
    }

    /** La oferta terminó: el asiento deja de pertenecer a ella. */
    public void quitarOferta(Instant now) {
        this.offerId = null;
        if (estado == SeatStatus.EN_OFERTA) {
            this.estado = SeatStatus.DISPONIBLE;
        }
        this.actualizadoEn = now;
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

    public UUID getUserId() {
        return userId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public Instant getVendidoEn() {
        return vendidoEn;
    }

    public String getBloqueoMotivo() {
        return bloqueoMotivo;
    }
}
