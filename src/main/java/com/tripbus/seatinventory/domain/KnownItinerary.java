package com.tripbus.seatinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Itinerario cuyos asientos ya fueron inicializados en el inventario. */
@Entity
@Table(name = "itineraries")
public class KnownItinerary {

    @Id
    private UUID id;

    @Column(nullable = false)
    private int capacidad;

    @Column(name = "fecha_salida")
    private Instant fechaSalida;

    @Column(nullable = false)
    private boolean cancelado;

    /** Empresa dueña del viaje (US-112). NULL en itinerarios anteriores a la migración V4. */
    @Column(name = "company_id")
    private UUID companyId;

    protected KnownItinerary() {
    }

    public KnownItinerary(UUID id, int capacidad, Instant fechaSalida, UUID companyId) {
        this.id = id;
        this.capacidad = capacidad;
        this.fechaSalida = fechaSalida;
        this.companyId = companyId;
    }

    public void cancelar() {
        this.cancelado = true;
    }

    /** ItineraryRescheduled: el viaje sale en otra fecha. */
    public void reprogramar(Instant nuevaSalida) {
        this.fechaSalida = nuevaSalida;
    }

    /** US-119: el viaje cambió de bus. */
    public void cambiarCapacidad(int nuevaCapacidad) {
        this.capacidad = nuevaCapacidad;
    }

    public UUID getId() {
        return id;
    }

    public int getCapacidad() {
        return capacidad;
    }

    public Instant getFechaSalida() {
        return fechaSalida;
    }

    public boolean isCancelado() {
        return cancelado;
    }

    public UUID getCompanyId() {
        return companyId;
    }
}
