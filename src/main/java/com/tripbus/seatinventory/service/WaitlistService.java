package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.WaitlistEntry;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.repository.WaitlistRepository;
import com.tripbus.seatinventory.web.dto.WaitlistResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** US-140: el pasajero se anota en la lista de espera de un viaje agotado, sale de ella y consulta sus turnos. */
@Service
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;
    private final SeatStateRepository seatRepository;
    private final SeatInventoryService inventoryService;
    private final Clock clock;

    public WaitlistService(WaitlistRepository waitlistRepository, SeatStateRepository seatRepository,
                           SeatInventoryService inventoryService, Clock clock) {
        this.waitlistRepository = waitlistRepository;
        this.seatRepository = seatRepository;
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    /**
     * Solo si hay menos asientos DISPONIBLE que la cantidad (si no, 409 SEATS_AVAILABLE: que los retenga) y
     * faltan más de 30 minutos para la salida. Un único turno activo por pasajero y viaje.
     */
    @Transactional
    public WaitlistResponse anotarse(UUID itineraryId, UUID userId, int cantidad) {
        KnownItinerary itinerary = inventoryService.ensureItinerary(itineraryId);
        // PostgreSQL guarda microsegundos: así la posición se calcula igual antes y después de guardar.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje fue cancelado");
        }
        if (WaitlistProcessor.cerradaPorSalida(itinerary, now)) {
            throw ApiException.conflict("WAITLIST_CLOSED",
                    "La lista de espera se cierra 30 minutos antes de la salida");
        }
        if (waitlistRepository.existsByItineraryIdAndUserIdAndEstadoIn(itineraryId, userId,
                WaitlistStatus.ACTIVOS)) {
            throw alreadyInWaitlist();
        }
        long disponibles = seatRepository.countByItineraryIdAndEstado(itineraryId, SeatStatus.DISPONIBLE);
        if (disponibles >= cantidad) {
            throw new ApiException(HttpStatus.CONFLICT, "SEATS_AVAILABLE",
                    "Hay asientos disponibles: puedes reservarlos directamente", Map.of("disponibles", disponibles));
        }
        try {
            WaitlistEntry entry = waitlistRepository.saveAndFlush(
                    new WaitlistEntry(UUID.randomUUID(), itineraryId, userId, cantidad, now));
            return toResponse(entry);
        } catch (DataIntegrityViolationException e) {
            // Dos solicitudes simultáneas del mismo pasajero: el índice único de turnos activos decide.
            throw alreadyInWaitlist();
        }
    }

    /** Sale de la fila. Si ya se le había ofrecido, libera esa retención (cuenta como liberada por el usuario). */
    @Transactional
    public void salir(UUID itineraryId, UUID userId) {
        WaitlistEntry entry = waitlistRepository.lockActivo(itineraryId, userId, WaitlistStatus.ACTIVOS)
                .orElseThrow(() -> ApiException.notFound("WAITLIST_NOT_FOUND",
                        "No estás en la lista de espera de este viaje"));
        UUID holdOfrecido = entry.getEstado() == WaitlistStatus.OFRECIDO ? entry.getHoldId() : null;
        entry.cancelar(clock.instant());
        waitlistRepository.flush();
        if (holdOfrecido != null) {
            inventoryService.releaseHold(holdOfrecido, userId, Hold.LIBERADA_POR_USUARIO);
        }
    }

    /** Mis turnos (los más recientes primero), con la posición en la fila de los que siguen esperando. */
    @Transactional(readOnly = true)
    public List<WaitlistResponse> misTurnos(UUID userId) {
        return waitlistRepository.findByUserIdOrderByCreadoEnDesc(userId).stream().map(this::toResponse).toList();
    }

    private WaitlistResponse toResponse(WaitlistEntry w) {
        Long posicion = w.getEstado() == WaitlistStatus.ESPERANDO
                ? waitlistRepository.posicion(w.getItineraryId(), w.getCreadoEn(), w.getId())
                : null;
        return new WaitlistResponse(w.getId(), w.getItineraryId(), w.getCantidad(), w.getEstado().name(), posicion,
                w.getHoldId(), w.getCreadoEn(), w.getActualizadoEn());
    }

    private static ApiException alreadyInWaitlist() {
        return ApiException.conflict("ALREADY_IN_WAITLIST", "Ya estás en la lista de espera de este viaje");
    }
}
