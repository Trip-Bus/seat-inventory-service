package com.tripbus.seatinventory.web.dto;

import com.tripbus.seatinventory.domain.SeatSpec;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * US-119: asientos del bus nuevo del viaje. US-141: con seats [{numero, categoria}] además actualiza la categoría
 * de cada asiento; con solo seatNumbers, los nuevos quedan ESTANDAR y los que siguen conservan la suya.
 */
public record RemapSeatsRequest(List<String> seatNumbers, List<SeatSpec> seats, @Positive int capacidad) {
}
