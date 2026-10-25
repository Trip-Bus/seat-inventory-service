package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.VoidReason;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.KnownItineraryRepository;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.web.dto.BlockSeatsResponse;
import com.tripbus.seatinventory.web.dto.RemapSeatsResponse;
import com.tripbus.seatinventory.web.dto.SeatView;
import com.tripbus.seatinventory.web.dto.SwapSeatResponse;
import com.tripbus.seatinventory.web.dto.VoidSaleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ciclo de vida del asiento después de la venta (Sprint 3): anular (US-151), cambiar de asiento (US-139),
 * bloquear (US-112) y cambiar el bus del viaje (US-119). Igual que {@link SeatInventoryService}, todo pasa
 * por las tres líneas de defensa: locks de Redis en orden ascendente, SELECT ... FOR UPDATE en orden
 * determinista y las restricciones/trigger de la base de datos.
 */
@Service
public class SeatLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SeatLifecycleService.class);

    static final String ORIGEN_SWAP = "SWAP";
    static final String ORIGEN_BLOCK = "BLOCK";
    static final String ORIGEN_UNBLOCK = "UNBLOCK";
    static final String ORIGEN_REMAP = "REMAP";
    private static final String ASIENTOS = "asientos";
    private static final String SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE";

    private final SeatStateRepository seatRepository;
    private final HoldRepository holdRepository;
    private final KnownItineraryRepository itineraryRepository;
    private final SeatAuditor auditor;
    private final DistributedSeatLock seatLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SeatLifecycleService(
            SeatStateRepository seatRepository,
            HoldRepository holdRepository,
            KnownItineraryRepository itineraryRepository,
            SeatAuditor auditor,
            DistributedSeatLock seatLock,
            TransactionTemplate tx,
            Clock clock) {
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.itineraryRepository = itineraryRepository;
        this.auditor = auditor;
        this.seatLock = seatLock;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ US-151 anular

    /**
     * Devuelve al inventario asientos vendidos a la reserva (VENDIDO -> DISPONIBLE). Solo los de esa reserva
     * (por holds.booking_id): si alguno es de otro dueño no cambia nada y responde 409 SEAT_NOT_OWNED.
     * Idempotente: los que esta reserva ya anuló se dejan como están y se responde su estado actual.
     */
    public VoidSaleResponse voidSale(UUID itineraryId, UUID bookingId, List<String> requested, VoidReason motivo) {
        List<String> seats = validSeats(requested);
        requireItinerary(itineraryId);
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats)) {
            return Objects.requireNonNull(tx.execute(status -> doVoid(itineraryId, bookingId, seats, motivo)));
        }
    }

    private VoidSaleResponse doVoid(UUID itineraryId, UUID bookingId, List<String> seats, VoidReason motivo) {
        Instant now = clock.instant();
        Map<UUID, Hold> holds = lockHolds(itineraryId, bookingId);
        List<SeatState> locked = lockExisting(itineraryId, seats);

        List<SeatState> aAnular = new ArrayList<>();
        List<String> ajenos = new ArrayList<>();
        for (SeatState seat : locked) {
            if (seat.vendidoA(holds.keySet())) {
                aAnular.add(seat);
            } else if (!auditor.yaLiberadoPor(seat, holds.keySet(), "VOID%")) {
                ajenos.add(seat.getSeatNumber());
            }
        }
        if (!ajenos.isEmpty()) {
            throw notOwned(ajenos);
        }

        Map<UUID, List<String>> anuladosPorHold = new LinkedHashMap<>();
        for (SeatState seat : aAnular) {
            UUID holdId = seat.getHoldId();
            UUID userId = seat.getUserId();
            seat.anular(now);
            auditor.registrar(seat, SeatStatus.VENDIDO, motivo.origen(), now, userId, holdId);
            holds.get(holdId).quitarAsiento(seat.getSeatNumber());
            anuladosPorHold.computeIfAbsent(holdId, k -> new ArrayList<>()).add(seat.getSeatNumber());
        }
        seatRepository.flush();
        anuladosPorHold.forEach((holdId, numeros) ->
                auditor.anunciarLiberados(holds.get(holdId), numeros, bookingId, motivo.name()));

        List<String> anulados = aAnular.stream().map(SeatState::getSeatNumber).sorted(SeatOrder.NATURAL).toList();
        return new VoidSaleResponse(itineraryId, bookingId, motivo.name(), anulados, views(locked));
    }

    // ------------------------------------------------------------------ US-139 cambiar de asiento

    /**
     * En UNA transacción toSeat pasa de DISPONIBLE a VENDIDO (mismo comprador y misma retención) y fromSeat
     * vuelve a DISPONIBLE: la reserva nunca queda con cero asientos ni con dos. Repetir un cambio ya hecho
     * responde el estado actual.
     */
    public SwapSeatResponse swapSeat(UUID itineraryId, UUID bookingId, String fromSeat, String toSeat) {
        String from = fromSeat == null ? "" : fromSeat.trim();
        String to = toSeat == null ? "" : toSeat.trim();
        if (from.isEmpty() || to.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEATS", "Debe indicar el asiento actual y el nuevo");
        }
        if (from.equals(to)) {
            throw ApiException.badRequest("SAME_SEAT", "El asiento nuevo debe ser distinto del actual");
        }
        KnownItinerary itinerary = requireItinerary(itineraryId);
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje fue cancelado");
        }
        List<String> seats = SeatOrder.sortedDistinct(List.of(from, to));
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats)) {
            return Objects.requireNonNull(tx.execute(status -> doSwap(itineraryId, bookingId, from, to, seats)));
        } catch (DataIntegrityViolationException e) {
            // Solo ocurre si las dos primeras líneas fallaran: la BD tiene la última palabra.
            throw ApiException.conflict(SEAT_UNAVAILABLE, "El asiento " + to + " ya no está disponible");
        }
    }

    private SwapSeatResponse doSwap(UUID itineraryId, UUID bookingId, String from, String to, List<String> seats) {
        Map<UUID, Hold> holds = lockHolds(itineraryId, bookingId);
        Map<String, SeatState> locked = lockExisting(itineraryId, seats).stream()
                .collect(Collectors.toMap(SeatState::getSeatNumber, Function.identity()));
        SeatState origen = locked.get(from);
        SeatState destino = locked.get(to);

        if (!origen.vendidoA(holds.keySet())) {
            if (destino.vendidoA(holds.keySet()) && auditor.yaLiberadoPor(origen, holds.keySet(), ORIGEN_SWAP)) {
                // El mismo cambio ya se hizo: se responde el estado actual.
                return new SwapSeatResponse(itineraryId, bookingId, destino.getHoldId(), from, to,
                        views(List.of(origen, destino)));
            }
            throw notOwned(List.of(from));
        }
        if (destino.getEstado() != SeatStatus.DISPONIBLE) {
            throw new ApiException(HttpStatus.CONFLICT, SEAT_UNAVAILABLE,
                    "El asiento " + to + " no está disponible", Map.of(ASIENTOS, List.of(to)));
        }

        Instant now = clock.instant();
        Hold hold = holds.get(origen.getHoldId());
        UUID userId = origen.getUserId();
        destino.venderEnCambioDe(origen, now);
        origen.anular(now);
        auditor.registrar(destino, SeatStatus.DISPONIBLE, ORIGEN_SWAP, now);
        auditor.registrar(origen, SeatStatus.VENDIDO, ORIGEN_SWAP, now, userId, hold.getId());
        hold.cambiarAsiento(from, to);
        seatRepository.flush();
        auditor.anunciarLiberados(hold, List.of(from), bookingId, "CAMBIO_DE_ASIENTO");

        return new SwapSeatResponse(itineraryId, bookingId, hold.getId(), from, to, views(List.of(origen, destino)));
    }

    // ------------------------------------------------------------------ US-112 bloquear

    /** Bloquea asientos DISPONIBLE. Si alguno está RETENIDO, VENDIDO o EN_OFERTA no cambia nada (409). */
    public BlockSeatsResponse blockSeats(UUID itineraryId, List<String> requested, String motivo, Actor actor) {
        List<String> seats = validSeats(requested);
        String razon = motivo == null ? "" : motivo.trim();
        if (razon.isEmpty()) {
            throw ApiException.badRequest("INVALID_REASON", "El motivo del bloqueo es obligatorio");
        }
        requireManager(requireItinerary(itineraryId), actor);
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats)) {
            return Objects.requireNonNull(tx.execute(status -> {
                Instant now = clock.instant();
                List<SeatState> locked = lockExisting(itineraryId, seats);
                List<String> ocupados = locked.stream()
                        .filter(s -> s.getEstado() != SeatStatus.DISPONIBLE && s.getEstado() != SeatStatus.BLOQUEADO)
                        .map(SeatState::getSeatNumber).sorted(SeatOrder.NATURAL).toList();
                if (!ocupados.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, SEAT_UNAVAILABLE,
                            "Solo se pueden bloquear asientos disponibles: " + String.join(", ", ocupados),
                            Map.of(ASIENTOS, ocupados));
                }
                return change(itineraryId, locked, SeatStatus.DISPONIBLE, seat -> {
                    seat.bloquear(razon, now);
                    auditor.registrar(seat, SeatStatus.DISPONIBLE, ORIGEN_BLOCK, now, actor.userId(), null);
                });
            }));
        }
    }

    /** Devuelve a la venta asientos BLOQUEADO; los que no están bloqueados quedan como están. */
    public BlockSeatsResponse unblockSeats(UUID itineraryId, List<String> requested, Actor actor) {
        List<String> seats = validSeats(requested);
        requireManager(requireItinerary(itineraryId), actor);
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats)) {
            return Objects.requireNonNull(tx.execute(status -> {
                Instant now = clock.instant();
                return change(itineraryId, lockExisting(itineraryId, seats), SeatStatus.BLOQUEADO, seat -> {
                    seat.desbloquear(now);
                    auditor.registrar(seat, SeatStatus.BLOQUEADO, ORIGEN_UNBLOCK, now, actor.userId(), null);
                });
            }));
        }
    }

    private BlockSeatsResponse change(UUID itineraryId, List<SeatState> seats, SeatStatus desde,
                                      Consumer<SeatState> cambio) {
        List<String> cambiados = new ArrayList<>();
        List<String> sinCambio = new ArrayList<>();
        for (SeatState seat : seats) {
            if (seat.getEstado() == desde) {
                cambio.accept(seat);
                cambiados.add(seat.getSeatNumber());
            } else {
                sinCambio.add(seat.getSeatNumber());
            }
        }
        seatRepository.flush();
        cambiados.sort(SeatOrder.NATURAL);
        sinCambio.sort(SeatOrder.NATURAL);
        return new BlockSeatsResponse(itineraryId, cambiados, sinCambio);
    }

    // ------------------------------------------------------------------ US-119 cambiar el bus

    /**
     * Aplica la lista de asientos del bus nuevo: agrega como DISPONIBLE los que no existían, borra los
     * DISPONIBLE que ya no existen y actualiza la capacidad. Si un asiento ocupado (VENDIDO, RETENIDO,
     * EN_OFERTA o BLOQUEADO) no existe en el bus nuevo no cambia nada: 409 SEATS_CONFLICT con la lista,
     * para que catalog le pida al operador reubicarlos primero.
     */
    public RemapSeatsResponse remapSeats(UUID itineraryId, List<String> requested, int capacidad) {
        List<String> nuevos = validSeats(requested);
        if (capacidad < nuevos.size()) {
            throw ApiException.badRequest("INVALID_CAPACITY",
                    "La capacidad (" + capacidad + ") no puede ser menor que el número de asientos (" + nuevos.size()
                            + ")");
        }
        requireItinerary(itineraryId);
        // Lock de Redis sobre los asientos que quedan, los que se borran y los que se crean.
        List<String> todos = SeatOrder.sortedDistinct(Stream.concat(
                seatRepository.findByItineraryId(itineraryId).stream().map(SeatState::getSeatNumber),
                nuevos.stream()).toList());
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, todos)) {
            return Objects.requireNonNull(tx.execute(status -> doRemap(itineraryId, nuevos, capacidad)));
        }
    }

    private RemapSeatsResponse doRemap(UUID itineraryId, List<String> nuevos, int capacidad) {
        Instant now = clock.instant();
        KnownItinerary itinerary = itineraryRepository.lockById(itineraryId)
                .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
        List<SeatState> actuales = seatRepository.lockByItinerary(itineraryId);
        Set<String> nuevosSet = new HashSet<>(nuevos);

        List<SeatState> sobran = actuales.stream().filter(s -> !nuevosSet.contains(s.getSeatNumber())).toList();
        List<String> conflictos = sobran.stream().filter(s -> s.getEstado() != SeatStatus.DISPONIBLE)
                .map(SeatState::getSeatNumber).sorted(SeatOrder.NATURAL).toList();
        if (!conflictos.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SEATS_CONFLICT",
                    "Asiento(s) ocupado(s) que no existen en el bus nuevo; reubícalos primero: "
                            + String.join(", ", conflictos), Map.of(ASIENTOS, conflictos));
        }

        seatRepository.deleteAll(sobran);
        Set<String> existentes = actuales.stream().map(SeatState::getSeatNumber).collect(Collectors.toSet());
        List<String> agregados = nuevos.stream().filter(n -> !existentes.contains(n)).toList();
        for (String numero : agregados) {
            SeatState seat = seatRepository.save(new SeatState(UUID.randomUUID(), itineraryId, numero, now));
            auditor.registrar(seat, null, ORIGEN_REMAP, now);
        }
        itinerary.cambiarCapacidad(capacidad);
        seatRepository.flush();

        List<String> eliminados = sobran.stream().map(SeatState::getSeatNumber).sorted(SeatOrder.NATURAL).toList();
        if (!agregados.isEmpty() || !eliminados.isEmpty()) {
            log.info("Itinerario {} con bus nuevo: +{} -{} asientos, capacidad {}", itineraryId, agregados.size(),
                    eliminados.size(), capacidad);
        }
        return new RemapSeatsResponse(itineraryId, capacidad, agregados, eliminados);
    }

    /** ItineraryRescheduled: el viaje sale en otra fecha. Un itinerario desconocido se ignora. */
    @Transactional
    public void onItineraryRescheduled(UUID itineraryId, Instant fechaSalida) {
        itineraryRepository.findById(itineraryId).ifPresentOrElse(
                it -> it.reprogramar(fechaSalida),
                () -> log.info("ItineraryRescheduled de un itinerario desconocido ({}); se ignora", itineraryId));
    }

    // ------------------------------------------------------------------ utilidades

    private static List<String> validSeats(List<String> requested) {
        return SeatInventoryService.validSeats(requested);
    }

    private KnownItinerary requireItinerary(UUID itineraryId) {
        return itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
    }

    private static void requireManager(KnownItinerary itinerary, Actor actor) {
        if (actor == null || !actor.puedeGestionar(itinerary)) {
            throw ApiException.forbidden("NOT_ITINERARY_MANAGER",
                    "Solo el operador de la empresa del viaje o un administrador puede bloquear sus asientos");
        }
    }

    /** Retenciones de la reserva en el viaje, bloqueadas ANTES que los asientos (mismo orden que ConfirmSale). */
    private Map<UUID, Hold> lockHolds(UUID itineraryId, UUID bookingId) {
        Map<UUID, Hold> holds = new LinkedHashMap<>();
        for (Hold hold : holdRepository.lockByItineraryAndBooking(itineraryId, bookingId)) {
            holds.put(hold.getId(), hold);
        }
        return holds;
    }

    /** SELECT ... FOR UPDATE de los asientos pedidos; 404 si alguno no existe en el bus. */
    private List<SeatState> lockExisting(UUID itineraryId, List<String> seats) {
        return SeatInventoryService.requireAllFound(seatRepository.lockSeats(itineraryId, seats), seats);
    }

    private static ApiException notOwned(List<String> seats) {
        return new ApiException(HttpStatus.CONFLICT, "SEAT_NOT_OWNED",
                "Asiento(s) que no pertenecen a esta reserva: " + String.join(", ", seats), Map.of(ASIENTOS, seats));
    }

    private static List<SeatView> views(Collection<SeatState> seats) {
        return seats.stream()
                .sorted((a, b) -> SeatOrder.NATURAL.compare(a.getSeatNumber(), b.getSeatNumber()))
                .map(s -> SeatInventoryService.toView(s, null, false))
                .toList();
    }
}
