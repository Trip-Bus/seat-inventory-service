package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.observability.InventoryMetrics;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.SeatOccupancy;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.Tramo;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.SeatOccupancyRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-142: las ocupaciones por tramo (seat_occupancies) de las retenciones y ventas, y su coherencia con
 * seat_states, siempre dentro de la transacción del cambio y después del SELECT ... FOR UPDATE de los asientos
 * (segunda línea de defensa). Toda retención y venta, completa o por tramo, escribe aquí; la restricción EXCLUDE
 * de la tabla es la tercera línea: dos ocupaciones del mismo asiento nunca se cruzan aunque la aplicación falle.
 * <p>
 * En seat_states el recorrido completo sigue como siempre (RETENIDO/VENDIDO con su retención); un asiento con
 * ocupaciones solo por tramos queda TRAMO, y vuelve a DISPONIBLE cuando se le va la última.
 */
@Component
public class SeatOccupancies {

    private final SeatOccupancyRepository repository;
    private final HoldRepository holdRepository;
    private final InventoryMetrics metrics;

    public SeatOccupancies(SeatOccupancyRepository repository, HoldRepository holdRepository,
                           InventoryMetrics metrics) {
        this.repository = repository;
        this.holdRepository = holdRepository;
        this.metrics = metrics;
    }

    /** Ocupa (RETENIDO) cada asiento de la retención en su tramo. Un cruce lo rechaza la BD (EXCLUDE). */
    public void ocupar(Hold hold, Instant now) {
        repository.saveAllAndFlush(hold.getSeatNumbers().stream().map(s -> new SeatOccupancy(hold, s, now)).toList());
    }

    /** Asientos (de los pedidos) que ya tienen una ocupación que se cruza con el tramo. */
    public List<String> cruzados(UUID itineraryId, Collection<String> seats, Tramo tramo) {
        return repository.cruzados(itineraryId, seats, tramo.desde(), tramo.hasta());
    }

    /** Ocupaciones del viaje que se cruzan con el tramo. */
    public List<SeatOccupancy> cruzanEn(UUID itineraryId, Tramo tramo) {
        return repository.cruzanEn(itineraryId, tramo.desde(), tramo.hasta());
    }

    /** La retención se cerró sin venderse (liberada, vencida o cancelada): sus ocupaciones RETENIDO se borran. */
    public void liberar(Hold hold) {
        List<SeatOccupancy> retenidas = repository.findByHoldId(hold.getId()).stream()
                .filter(o -> o.getEstado() == SeatStatus.RETENIDO).toList();
        repository.deleteAll(retenidas);
        repository.flush();
    }

    /** ConfirmSale: las ocupaciones de la retención pasan a VENDIDO. Devuelve cuántas hay. */
    public int vender(Hold hold) {
        List<SeatOccupancy> ocupaciones = repository.findByHoldId(hold.getId());
        ocupaciones.forEach(SeatOccupancy::vender);
        repository.flush();
        return ocupaciones.size();
    }

    /** Ocupaciones RETENIDO de la retención (las que ConfirmSale va a vender). */
    public long retenidas(Hold hold) {
        return repository.findByHoldId(hold.getId()).stream().filter(o -> o.getEstado() == SeatStatus.RETENIDO)
                .count();
    }

    /** La ocupación VENDIDO del asiento que compró la reserva (alguna de sus retenciones), si la hay. */
    public Optional<SeatOccupancy> vendidaA(SeatState seat, Collection<UUID> holdIds) {
        if (holdIds.isEmpty()) {
            return Optional.empty();
        }
        return repository.vendidaA(seat.getItineraryId(), seat.getSeatNumber(), holdIds);
    }

    /** US-151: la venta del asiento se anula (la ocupación se borra). */
    public void quitar(SeatOccupancy ocupacion) {
        repository.delete(ocupacion);
        repository.flush();
    }

    /** US-139: la venta pasa a otro asiento en el mismo tramo (si se cruza con otra, la BD la rechaza). */
    public void mover(SeatOccupancy ocupacion, String otroAsiento) {
        ocupacion.moverA(otroAsiento);
        repository.flush();
    }

    /**
     * Después de quitarle ocupaciones por tramo a un asiento TRAMO: si ya no le queda ninguna, vuelve a DISPONIBLE.
     * Devuelve true si cambió.
     */
    public boolean liberarSiQuedoLibre(SeatState seat, Instant now) {
        if (seat.getEstado() == SeatStatus.TRAMO
                && !repository.existsByItineraryIdAndSeatNumber(seat.getItineraryId(), seat.getSeatNumber())) {
            seat.liberarTramos(now);
            return true;
        }
        return false;
    }

    /** Retenciones de la reserva en el viaje, bloqueadas ANTES que los asientos (mismo orden que ConfirmSale). */
    public Map<UUID, Hold> lockHolds(UUID itineraryId, UUID bookingId) {
        Map<UUID, Hold> holds = new LinkedHashMap<>();
        for (Hold hold : holdRepository.lockByItineraryAndBooking(itineraryId, bookingId)) {
            holds.put(hold.getId(), hold);
        }
        return holds;
    }

    /** HT-10: la base de datos rechazó una doble venta (tripbus_doble_venta_bloqueada_total). */
    public void dobleVentaBloqueada() {
        metrics.dobleVentaBloqueada();
    }
}
