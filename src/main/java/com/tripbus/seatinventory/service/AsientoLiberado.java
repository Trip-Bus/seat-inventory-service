package com.tripbus.seatinventory.service;

import java.util.UUID;

/**
 * Evento interno (Spring, no del bus): un asiento del viaje volvió a DISPONIBLE (retención vencida o liberada,
 * venta anulada, asiento desbloqueado o agregado por un cambio de bus). {@link WaitlistScheduler} procesa la
 * lista de espera del viaje cuando la transacción del cambio confirma (US-140).
 */
public record AsientoLiberado(UUID itineraryId) {
}
