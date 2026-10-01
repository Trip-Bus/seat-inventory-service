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

    protected KnownItinerary() {
    }

    public void cancelar() {
        this.cancelado = true;
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
}
