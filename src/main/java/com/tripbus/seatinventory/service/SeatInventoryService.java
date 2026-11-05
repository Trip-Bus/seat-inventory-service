package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.client.CatalogClient;
import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.Outbox;
import com.tripbus.seatinventory.common.observability.InventoryMetrics;
import com.tripbus.seatinventory.config.InventoryProperties;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.HoldStatus;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatOccupancy;
import com.tripbus.seatinventory.domain.SeatSpec;
import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.Tramo;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import com.tripbus.seatinventory.events.InventoryEvents;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.KnownItineraryRepository;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.repository.WaitlistRepository;
import com.tripbus.seatinventory.web.dto.AvailabilityResponse;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import com.tripbus.seatinventory.web.dto.OfferSeatsResponse;
import com.tripbus.seatinventory.web.dto.SeatStatesResponse;
import com.tripbus.seatinventory.web.dto.SeatView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Corazón de la concurrencia de TripBus. Implementa las operaciones del doc:
 * GetSeatStates, GetSeatState, HoldSeats, ReleaseHold, ExtendHold, ConfirmSale y
 * ReleaseExpiredHolds (este último en {@link ExpiredHoldsSweeper}).
 */
@Service
public class SeatInventoryService {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryService.class);

    private final SeatStateRepository seatRepository;
    private final HoldRepository holdRepository;
    private final KnownItineraryRepository itineraryRepository;
    private final WaitlistRepository waitlistRepository;
    private final SeatOccupancies occupancies;
    private final SeatAuditor auditor;
    private final InventoryInitializer initializer;
    private final CatalogClient catalogClient;
    private final DistributedSeatLock seatLock;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final InventoryProperties properties;
    private final InventoryMetrics metrics;
    private final Clock clock;

    public SeatInventoryService(
            SeatStateRepository seatRepository,
            HoldRepository holdRepository,
            KnownItineraryRepository itineraryRepository,
            WaitlistRepository waitlistRepository,
            SeatOccupancies occupancies,
            SeatAuditor auditor,
            InventoryInitializer initializer,
            CatalogClient catalogClient,
            DistributedSeatLock seatLock,
            Outbox outbox,
            TransactionTemplate tx,
            InventoryProperties properties,
            InventoryMetrics metrics,
            Clock clock) {
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.itineraryRepository = itineraryRepository;
        this.waitlistRepository = waitlistRepository;
        this.occupancies = occupancies;
        this.auditor = auditor;
        this.initializer = initializer;
        this.catalogClient = catalogClient;
        this.seatLock = seatLock;
        this.outbox = outbox;
        this.tx = tx;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consultas

    /** GetSeatStates (US-016/US-017). actor == null: consulta anónima. */
    @Transactional(readOnly = true)
    public SeatStatesResponse getSeatStates(UUID itineraryId, Actor actor) {
        return getSeatStates(itineraryId, actor, Tramo.COMPLETO);
    }

    /**
     * GetSeatStates en un tramo (US-142). Sin tramo, el estado de siempre (recorrido completo, con TRAMO para los
     * asientos ocupados solo en parte). En un tramo, cada asiento sale RETENIDO o VENDIDO si alguna ocupación lo
     * cruza y DISPONIBLE si no; EN_OFERTA y BLOQUEADO no se venden por tramos y salen como tales.
     */
    @Transactional(readOnly = true)
    public SeatStatesResponse getSeatStates(UUID itineraryId, Actor actor, Tramo tramo) {
        KnownItinerary itinerary = ensureItinerary(itineraryId);
        UUID requesterId = actor != null ? actor.userId() : null;
        boolean verMotivo = actor != null && actor.puedeGestionar(itinerary);
        List<SeatState> seats = new ArrayList<>(seatRepository.findByItineraryId(itineraryId));
        seats.sort((a, b) -> SeatOrder.NATURAL.compare(a.getSeatNumber(), b.getSeatNumber()));
        Map<String, SeatOccupancy> enTramo = new HashMap<>();
        if (!tramo.esCompleto()) {
            // Si dos ocupaciones cruzan el tramo, manda la vendida.
            for (SeatOccupancy o : occupancies.cruzanEn(itineraryId, tramo)) {
                enTramo.merge(o.getSeatNumber(), o, (a, b) -> a.getEstado() == SeatStatus.VENDIDO ? a : b);
            }
        }
        Map<SeatStatus, Long> conteo = new EnumMap<>(SeatStatus.class);
        for (SeatStatus s : SeatStatus.values()) {
            conteo.put(s, 0L);
        }
        List<SeatView> views = new ArrayList<>();
        for (SeatState s : seats) {
            SeatView view = tramo.esCompleto() ? toView(s, requesterId, verMotivo)
                    : toViewEnTramo(s, enTramo.get(s.getSeatNumber()), requesterId, verMotivo);
            conteo.merge(SeatStatus.valueOf(view.estado()), 1L, Long::sum);
            views.add(view);
        }
        return new SeatStatesResponse(itineraryId, itinerary.getCapacidad(),
                new SeatStatesResponse.Resumen(conteo.get(SeatStatus.DISPONIBLE), conteo.get(SeatStatus.RETENIDO),
                        conteo.get(SeatStatus.VENDIDO), conteo.get(SeatStatus.EN_OFERTA),
                        conteo.get(SeatStatus.BLOQUEADO), conteo.get(SeatStatus.TRAMO)),
                views);
    }

    /** GetSeatState. actor == null: consulta anónima. */
    @Transactional(readOnly = true)
    public SeatView getSeatState(UUID itineraryId, String seatNumber, Actor actor) {
        KnownItinerary itinerary = ensureItinerary(itineraryId);
        UUID requesterId = actor != null ? actor.userId() : null;
        boolean verMotivo = actor != null && actor.puedeGestionar(itinerary);
        return seatRepository.findByItineraryIdAndSeatNumber(itineraryId, seatNumber)
                .map(s -> toView(s, requesterId, verMotivo))
                .orElseThrow(() -> ApiException.notFound("SEAT_NOT_FOUND", "El asiento no existe en este bus"));
    }

    /** Disponibilidad agregada para la búsqueda de Catalog (US-008). */
    @Transactional(readOnly = true)
    public List<AvailabilityResponse> availability(Collection<UUID> itineraryIds) {
        return availability(itineraryIds, Tramo.COMPLETO);
    }

    /**
     * Disponibilidad en un tramo (US-142): un asiento está libre si es DISPONIBLE o TRAMO y ninguna ocupación lo
     * cruza. Las ofertas son del recorrido completo, así que en un tramo enOferta es 0. Sin tramo, la de siempre.
     */
    @Transactional(readOnly = true)
    public List<AvailabilityResponse> availability(Collection<UUID> itineraryIds, Tramo tramo) {
        if (itineraryIds.isEmpty()) {
            return List.of();
        }
        if (!tramo.esCompleto()) {
            Map<UUID, AvailabilityResponse> porViaje = new HashMap<>();
            for (SeatStateRepository.TramoAvailabilityRow row
                    : seatRepository.disponibilidadEnTramo(itineraryIds, tramo.desde(), tramo.hasta())) {
                porViaje.put(row.getItineraryId(),
                        new AvailabilityResponse(row.getItineraryId(), row.getDisponibles(), 0, row.getTotal()));
            }
            return new LinkedHashSet<>(itineraryIds).stream().map(porViaje::get).filter(Objects::nonNull)
                    .filter(a -> a.total() > 0).toList();
        }
        Map<UUID, long[]> acc = new LinkedHashMap<>();
        for (UUID id : itineraryIds) {
            acc.put(id, new long[3]);
        }
        for (SeatStateRepository.AvailabilityRow row : seatRepository.countByEstado(itineraryIds)) {
            long[] v = acc.get(row.getItineraryId());
            if (v == null) {
                continue;
            }
            if (row.getEstado() == SeatStatus.BLOQUEADO) {
                // US-112: un asiento bloqueado no está a la venta, así que no cuenta ni en el total.
                continue;
            }
            v[2] += row.getTotal();
            if (row.getEstado() == SeatStatus.DISPONIBLE) {
                v[0] += row.getTotal();
            } else if (row.getEstado() == SeatStatus.EN_OFERTA) {
                v[0] += row.getTotal();
                v[1] += row.getTotal();
            }
        }
        return acc.entrySet().stream()
                .filter(e -> e.getValue()[2] > 0)
                .map(e -> new AvailabilityResponse(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(UUID holdId, UUID requesterId) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe"));
        if (requesterId != null && !hold.getUserId().equals(requesterId)) {
            throw ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe");
        }
        return toResponse(hold);
    }

    // ------------------------------------------------------------------ HoldSeats

    /**
     * HoldSeats (US-022/US-026). Tres líneas de defensa contra la doble venta:
     * 1) lock distribuido en Redis por asiento, 2) SELECT ... FOR UPDATE en orden y
     * 3) restricciones de la BD (único por asiento + trigger anti doble venta + EXCLUDE de los tramos).
     */
    public HoldResponse holdSeats(UUID itineraryId, List<String> requestedSeats, UUID userId, UUID offerId) {
        return holdSeats(itineraryId, requestedSeats, userId, offerId, Tramo.COMPLETO);
    }

    /**
     * HoldSeats en un tramo (US-142). Las mismas tres líneas: el lock de Redis es por asiento y tramo (dos pedidos
     * del mismo asiento y tramo compiten como siempre), el FOR UPDATE de la fila del asiento ordena a todos los que
     * piden ese asiento, sea cual sea su tramo, y la restricción EXCLUDE rechaza cualquier cruce. Las ofertas son
     * del recorrido completo.
     */
    public HoldResponse holdSeats(UUID itineraryId, List<String> requestedSeats, UUID userId, UUID offerId,
                                  Tramo tramo) {
        // HT-10: tripbus_retencion_segundos mide la retención completa (locks + transacción), también si falla.
        return metrics.medirRetencion(() -> retener(itineraryId, requestedSeats, userId, offerId, tramo));
    }

    private HoldResponse retener(UUID itineraryId, List<String> requestedSeats, UUID userId, UUID offerId,
                                 Tramo tramo) {
        List<String> seats = validSeats(requestedSeats);
        if (seats.size() > properties.maxSeatsPerHold()) {
            throw ApiException.badRequest("TOO_MANY_SEATS",
                    "Solo se pueden retener hasta " + properties.maxSeatsPerHold() + " asientos por compra");
        }
        if (offerId != null && !tramo.esCompleto()) {
            throw ApiException.badRequest("INVALID_SEGMENT", "Los asientos en oferta se venden para todo el recorrido");
        }
        KnownItinerary itinerary = ensureItinerary(itineraryId);
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje fue cancelado");
        }

        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats, tramo)) {
            HoldResponse response = tx.execute(status ->
                    toResponse(doHold(itineraryId, seats, userId, offerId, tramo)));
            metrics.retencion(InventoryMetrics.Resultado.CREADA);
            return Objects.requireNonNull(response);
        } catch (DataIntegrityViolationException e) {
            // Solo ocurre si las dos primeras líneas fallaran: la BD tiene la última palabra.
            metrics.dobleVentaBloqueada();
            throw ApiException.conflict("SEAT_UNAVAILABLE", "El asiento ya no está disponible");
        }
    }

    private Hold doHold(UUID itineraryId, List<String> seats, UUID userId, UUID offerId, Tramo tramo) {
        Instant now = clock.instant();
        List<SeatState> locked = requireAllFound(seatRepository.lockSeats(itineraryId, seats), seats);
        // US-018: un asiento ocupado (retenido, vendido o de otra oferta; o en un tramo que se cruza) no se puede
        // seleccionar.
        List<String> ocupados = ocupados(itineraryId, locked, offerId, tramo);
        if (!ocupados.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE",
                    "Asiento(s) no disponible(s): " + String.join(", ", ocupados), Map.of("asientos", ocupados));
        }

        Hold hold = holdRepository.save(new Hold(UUID.randomUUID(), itineraryId, userId, offerId, seats, now,
                properties.holdTtl()).enTramo(tramo));
        for (SeatState seat : locked) {
            SeatStatus anterior = seat.getEstado();
            if (tramo.esCompleto()) {
                seat.retener(hold.getId(), userId, now);
            } else {
                seat.ocuparTramo(now);
            }
            auditor.registrar(seat, anterior, "HOLD", now, userId, hold.getId());
        }
        seatRepository.flush();
        occupancies.ocupar(hold, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", itineraryId);
        payload.put("seatNumbers", seats);
        payload.put("userId", userId);
        payload.put("holdId", hold.getId());
        payload.put("offerId", offerId);
        payload.put("expiraEn", hold.getExpiraEn());
        outbox.add(InventoryEvents.SEAT_HELD, conTramo(payload, hold));
        return hold;
    }

    private List<String> ocupados(UUID itineraryId, List<SeatState> locked, UUID offerId, Tramo tramo) {
        if (tramo.esCompleto()) {
            return locked.stream().filter(s -> !s.disponiblePara(offerId)).map(SeatState::getSeatNumber).toList();
        }
        Set<String> cruzados = new HashSet<>(occupancies.cruzados(itineraryId,
                locked.stream().map(SeatState::getSeatNumber).toList(), tramo));
        return locked.stream().filter(s -> !s.admiteTramos() || cruzados.contains(s.getSeatNumber()))
                .map(SeatState::getSeatNumber).toList();
    }

    /** US-142: los eventos de una retención por tramo dicen el tramo; los del recorrido completo, como siempre. */
    private static Map<String, Object> conTramo(Map<String, Object> payload, Hold hold) {
        if (!hold.esCompleta()) {
            payload.put("desdeOrden", hold.getTramo().desde());
            payload.put("hastaOrden", hold.getTramo().hasta());
        }
        return payload;
    }

    /** Asientos de la retención bloqueados FOR UPDATE (los del recorrido completo tienen su hold_id). */
    private List<SeatState> lockSeatsOf(Hold hold) {
        return hold.esCompleta() ? seatRepository.lockByHold(hold.getId())
                : seatRepository.lockSeats(hold.getItineraryId(), hold.getSeatNumbers());
    }

    // ------------------------------------------------------------------ ReleaseHold

    /**
     * ReleaseHold. Idempotente: liberar una retención ya liberada/expirada no falla.
     * requesterId == null significa llamada interna (otro servicio).
     */
    @Transactional
    public HoldResponse releaseHold(UUID holdId, UUID requesterId, String motivo) {
        Hold hold = holdRepository.lockById(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe"));
        if (requesterId != null && !hold.getUserId().equals(requesterId)) {
            throw ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe");
        }
        if (hold.getEstado() == HoldStatus.CONFIRMADA) {
            throw ApiException.conflict("HOLD_ALREADY_CONFIRMED", "La compra de este asiento ya fue confirmada");
        }
        if (hold.getEstado() == HoldStatus.ACTIVA) {
            closeHold(hold, HoldStatus.LIBERADA, motivo != null ? motivo : Hold.LIBERADA_POR_USUARIO);
        }
        return toResponse(hold);
    }

    /** Libera los asientos de una retención activa y registra los eventos en el outbox. Requiere transacción. */
    void closeHold(Hold hold, HoldStatus estadoFinal, String motivo) {
        Instant now = clock.instant();
        List<String> liberados = new ArrayList<>();
        List<SeatState> seats = lockSeatsOf(hold);
        occupancies.liberar(hold);
        for (SeatState seat : seats) {
            SeatStatus anterior = seat.getEstado();
            if (hold.esCompleta() && anterior == SeatStatus.RETENIDO) {
                seat.liberar(now);
                audit(seat, anterior, estadoFinal.name(), now);
                liberados.add(seat.getSeatNumber());
            } else if (!hold.esCompleta() && anterior == SeatStatus.TRAMO) {
                // El tramo queda libre; el asiento vuelve a DISPONIBLE si no le queda ninguna otra ocupación.
                occupancies.liberarSiQuedoLibre(seat, now);
                auditor.registrar(seat, anterior, estadoFinal.name(), now, hold.getUserId(), hold.getId());
                liberados.add(seat.getSeatNumber());
            }
        }
        hold.cerrar(estadoFinal, motivo);
        metrics.retencion(estadoFinal == HoldStatus.EXPIRADA
                ? InventoryMetrics.Resultado.EXPIRADA : InventoryMetrics.Resultado.LIBERADA);
        // US-140: si la retención era la oferta de un turno de la lista de espera, el turno se cierra con ella.
        waitlistRepository.cerrarOfrecido(hold.getId(), WaitlistStatus.trasRetencion(estadoFinal), now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", hold.getItineraryId());
        payload.put("seatNumbers", liberados);
        payload.put("holdId", hold.getId());
        payload.put("offerId", hold.getOfferId());
        payload.put("motivo", motivo);
        if (estadoFinal == HoldStatus.EXPIRADA) {
            Map<String, Object> expired = new LinkedHashMap<>();
            expired.put("itineraryId", hold.getItineraryId());
            expired.put("seatNumbers", liberados);
            expired.put("holdId", hold.getId());
            expired.put("offerId", hold.getOfferId());
            outbox.add(InventoryEvents.HOLD_EXPIRED, conTramo(expired, hold));
        }
        outbox.add(InventoryEvents.SEAT_RELEASED, conTramo(payload, hold));
    }

    // ------------------------------------------------------------------ ExtendHold

    /** ExtendHold: una sola vez, máximo 5 minutos adicionales. */
    @Transactional
    public HoldResponse extendHold(UUID holdId, UUID requesterId) {
        Hold hold = holdRepository.lockById(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe"));
        if (!hold.getUserId().equals(requesterId)) {
            throw ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe");
        }
        if (hold.getEstado() != HoldStatus.ACTIVA || hold.vencida(clock.instant())) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "La retención ya no está activa");
        }
        if (hold.isExtendido()) {
            throw ApiException.conflict("HOLD_ALREADY_EXTENDED", "La retención solo se puede extender una vez");
        }
        hold.extender(properties.holdExtension());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", hold.getItineraryId());
        payload.put("seatNumbers", hold.getSeatNumbers());
        payload.put("userId", hold.getUserId());
        payload.put("holdId", hold.getId());
        payload.put("offerId", hold.getOfferId());
        payload.put("expiraEn", hold.getExpiraEn());
        payload.put("extendido", true);
        outbox.add(InventoryEvents.SEAT_HELD, payload);
        return toResponse(hold);
    }

    // ------------------------------------------------------------------ ConfirmSale

    /** ConfirmSale: pasa los asientos de RETENIDO a VENDIDO. Idempotente por bookingId. */
    @Transactional
    public HoldResponse confirmSale(UUID holdId, UUID bookingId) {
        Hold hold = holdRepository.lockById(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe"));
        if (hold.getEstado() == HoldStatus.CONFIRMADA) {
            if (bookingId.equals(hold.getBookingId())) {
                return toResponse(hold);
            }
            throw ApiException.conflict("HOLD_ALREADY_CONFIRMED", "La retención ya fue usada en otra compra");
        }
        Instant now = clock.instant();
        if (hold.getEstado() != HoldStatus.ACTIVA || hold.vencida(now)) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "La retención expiró o fue liberada; el asiento se perdió");
        }
        List<SeatState> seats = lockSeatsOf(hold);
        // Recorrido completo: los asientos RETENIDO con esta retención. Por tramo: TRAMO y con su ocupación RETENIDO.
        SeatStatus esperado = hold.esCompleta() ? SeatStatus.RETENIDO : SeatStatus.TRAMO;
        if (seats.size() != hold.getSeatNumbers().size() || seats.stream().anyMatch(s -> s.getEstado() != esperado)
                || occupancies.retenidas(hold) != seats.size()) {
            throw ApiException.conflict("HOLD_INCONSISTENT", "Los asientos de la retención ya no están retenidos");
        }
        for (SeatState seat : seats) {
            if (hold.esCompleta()) {
                seat.vender(now);
            }
            auditor.registrar(seat, esperado, "SALE", now, hold.getUserId(), hold.getId());
        }
        seatRepository.flush();
        occupancies.vender(hold);
        hold.confirmar(bookingId);
        metrics.retencion(InventoryMetrics.Resultado.CONFIRMADA);
        waitlistRepository.cerrarOfrecido(holdId, WaitlistStatus.CONVERTIDO, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", hold.getItineraryId());
        payload.put("seatNumbers", hold.getSeatNumbers());
        payload.put("userId", hold.getUserId());
        payload.put("holdId", hold.getId());
        payload.put("offerId", hold.getOfferId());
        payload.put("bookingId", bookingId);
        outbox.add(InventoryEvents.SEAT_SOLD, conTramo(payload, hold));
        return toResponse(hold);
    }

    // ------------------------------------------------------------------ ofertas

    /** Reserva asientos DISPONIBLES para una oferta relámpago (los pasa a EN_OFERTA). */
    public OfferSeatsResponse reserveOfferSeats(UUID offerId, UUID itineraryId, List<String> requested) {
        List<String> seats = SeatOrder.sortedDistinct(requested);
        KnownItinerary itinerary = ensureItinerary(itineraryId);
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje fue cancelado");
        }
        try (DistributedSeatLock.SeatLocks ignored = seatLock.acquireAll(itineraryId, seats)) {
            return tx.execute(status -> {
                Instant now = clock.instant();
                List<String> reservados = new ArrayList<>();
                List<String> omitidos = new ArrayList<>(seats);
                for (SeatState seat : seatRepository.lockSeats(itineraryId, seats)) {
                    if (seat.getEstado() == SeatStatus.DISPONIBLE
                            || (seat.getEstado() == SeatStatus.EN_OFERTA && offerId.equals(seat.getOfferId()))) {
                        SeatStatus anterior = seat.getEstado();
                        seat.ponerEnOferta(offerId, now);
                        audit(seat, anterior, "OFFER", now);
                        reservados.add(seat.getSeatNumber());
                        omitidos.remove(seat.getSeatNumber());
                    }
                }
                return new OfferSeatsResponse(offerId, itineraryId, reservados, omitidos);
            });
        }
    }

    /** OfferClosed: los asientos que no se vendieron vuelven a la venta normal. */
    @Transactional
    public void releaseOfferSeats(UUID offerId) {
        Instant now = clock.instant();
        for (SeatState seat : seatRepository.lockByOffer(offerId)) {
            SeatStatus anterior = seat.getEstado();
            seat.quitarOferta(now);
            if (anterior != seat.getEstado()) {
                audit(seat, anterior, "OFFER_CLOSED", now);
            }
        }
    }

    // ------------------------------------------------------------------ eventos de otros servicios

    /** ItineraryPublished: crea los registros de asientos del nuevo itinerario (y guarda su empresa, US-112). */
    public void onItineraryPublished(UUID itineraryId, List<String> seatNumbers, Instant fechaSalida,
                                     UUID companyId) {
        initializer.initialize(itineraryId, seatNumbers, fechaSalida, companyId);
    }

    /** ItineraryPublished con seats[] (US-141): cada asiento con su categoría. */
    public void onItineraryPublishedWithSeats(UUID itineraryId, List<SeatSpec> seats, Instant fechaSalida,
                                              UUID companyId) {
        initializer.initializeSeats(itineraryId, seats, fechaSalida, companyId);
    }

    /** ItineraryCancelled: cancela retenciones activas y libera sus asientos. */
    @Transactional
    public void onItineraryCancelled(UUID itineraryId, String motivo) {
        itineraryRepository.findById(itineraryId).ifPresent(KnownItinerary::cancelar);
        for (UUID holdId : holdRepository.findIdsByItineraryIdAndEstado(itineraryId, HoldStatus.ACTIVA)) {
            holdRepository.lockById(holdId)
                    .filter(h -> h.getEstado() == HoldStatus.ACTIVA)
                    .ifPresent(h -> closeHold(h, HoldStatus.CANCELADA, "ITINERARIO_CANCELADO: " + motivo));
        }
    }

    // ------------------------------------------------------------------ ReleaseExpiredHolds

    /** Expira UNA retención vencida (cada una en su propia transacción). */
    @Transactional
    public boolean expireHold(UUID holdId) {
        return holdRepository.lockById(holdId)
                .filter(h -> h.getEstado() == HoldStatus.ACTIVA && h.vencida(clock.instant()))
                .map(h -> {
                    closeHold(h, HoldStatus.EXPIRADA, "EXPIRADA");
                    return true;
                })
                .orElse(false);
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Devuelve el itinerario conocido. Si aún no llegó el evento ItineraryPublished,
     * lo inicializa consultando a Catalog (que además valida que exista).
     */
    KnownItinerary ensureItinerary(UUID itineraryId) {
        return itineraryRepository.findById(itineraryId).orElseGet(() -> {
            if (itineraryRepository.isArchived(itineraryId)) {
                // HT-08: ya salió y se archivó; no se vuelve a inicializar desde catalog.
                throw new ApiException(HttpStatus.GONE, "ITINERARY_ARCHIVED", "El viaje ya salió y fue archivado");
            }
            CatalogClient.SeatMap seatMap = catalogClient.seatMap(itineraryId)
                    .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
            log.info("Inicializando de forma perezosa el inventario del itinerario {}", itineraryId);
            initializer.initialize(itineraryId, seatMap.asientos(), null, seatMap.companyId());
            return itineraryRepository.findById(itineraryId)
                    .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
        });
    }

    /** Asientos pedidos, sin repetir y en orden natural; 400 INVALID_SEATS si no hay ninguno válido. */
    static List<String> validSeats(List<String> requested) {
        List<String> seats = SeatOrder.sortedDistinct(requested == null ? List.of() : requested);
        if (seats.isEmpty() || seats.stream().anyMatch(String::isBlank)) {
            throw ApiException.badRequest("INVALID_SEATS", "Debe indicar al menos un asiento válido");
        }
        return seats;
    }

    /** 404 SEAT_NOT_FOUND (con details.asientos) si alguno de los asientos pedidos no existe en el bus. */
    static List<SeatState> requireAllFound(List<SeatState> locked, List<String> seats) {
        if (locked.size() != seats.size()) {
            Set<String> found = locked.stream().map(SeatState::getSeatNumber).collect(Collectors.toSet());
            List<String> missing = seats.stream().filter(s -> !found.contains(s)).toList();
            throw new ApiException(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND",
                    "Asiento(s) inexistente(s) en este bus: " + String.join(", ", missing),
                    Map.of("asientos", missing));
        }
        return locked;
    }

    /** Registra el cambio en seat_audit_log y lo anuncia (SeatStateChanged) vía outbox, en la misma transacción. */
    private void audit(SeatState seat, SeatStatus anterior, String origen, Instant now) {
        auditor.registrar(seat, anterior, origen, now);
    }

    /** US-112: el motivo de un bloqueo solo lo ve quien gestiona el viaje (operador de la empresa o ADMIN). */
    static SeatView toView(SeatState s, UUID requesterId, boolean verMotivo) {
        boolean propio = requesterId != null && requesterId.equals(s.getUserId());
        return new SeatView(s.getSeatNumber(), s.getEstado().name(), s.getCategoria(), s.getOfferId(), propio,
                verMotivo ? s.getBloqueoMotivo() : null);
    }

    /**
     * US-142: el asiento visto en un tramo: BLOQUEADO y EN_OFERTA como siempre; si una ocupación cruza el tramo, su
     * estado (RETENIDO o VENDIDO); si no, DISPONIBLE (aunque esté ocupado en otros tramos).
     */
    static SeatView toViewEnTramo(SeatState s, SeatOccupancy cruce, UUID requesterId, boolean verMotivo) {
        if (s.getEstado() == SeatStatus.BLOQUEADO || s.getEstado() == SeatStatus.EN_OFERTA) {
            return toView(s, requesterId, verMotivo);
        }
        SeatStatus estado = cruce != null ? cruce.getEstado() : SeatStatus.DISPONIBLE;
        boolean propio = cruce != null && requesterId != null && requesterId.equals(cruce.getUserId());
        return new SeatView(s.getSeatNumber(), estado.name(), s.getCategoria(), null, propio, null);
    }

    private HoldResponse toResponse(Hold hold) {
        long restantes = hold.getEstado() == HoldStatus.ACTIVA
                ? Math.max(0, Duration.between(clock.instant(), hold.getExpiraEn()).toSeconds())
                : 0;
        Tramo tramo = hold.esCompleta() ? null : hold.getTramo();
        return new HoldResponse(hold.getId(), hold.getItineraryId(), List.copyOf(hold.getSeatNumbers()),
                hold.getUserId(), hold.getOfferId(), hold.getBookingId(), hold.getEstado().name(), hold.getCreadoEn(),
                hold.getExpiraEn(), hold.isExtendido(), restantes, tramo != null ? tramo.desde() : null,
                tramo != null ? tramo.hasta() : null);
    }
}
