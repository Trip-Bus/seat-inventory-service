package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.common.events.Outbox;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.domain.WaitlistEntry;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import com.tripbus.seatinventory.events.InventoryEvents;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.repository.WaitlistRepository;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-140: ofrece los asientos que se liberan a la lista de espera del viaje, en orden de llegada. Cada oferta
 * corre en su propia transacción y bajo {@code pg_advisory_xact_lock} del viaje: dos liberaciones simultáneas
 * nunca procesan la misma fila a la vez, así que el mismo asiento no se ofrece a dos personas. La retención se
 * crea por {@link SeatInventoryService#holdSeats} (las tres líneas de defensa de siempre).
 */
@Service
public class WaitlistProcessor {

    private static final Logger log = LoggerFactory.getLogger(WaitlistProcessor.class);
    /** La fila se cierra 30 minutos antes de la salida. */
    public static final Duration CIERRE_ANTES_DE_SALIDA = Duration.ofMinutes(30);
    /** Primera mitad de la llave del advisory lock ("WAIT"): separa estos locks de otros usos en la BD. */
    static final int NAMESPACE = 0x5741_4954;
    /** Reintentos cuando otro pasajero toma un asiento libre entre la lectura y la retención. */
    static final int MAX_INTENTOS = 3;

    private final WaitlistRepository waitlistRepository;
    private final SeatStateRepository seatRepository;
    private final SeatInventoryService inventoryService;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WaitlistProcessor(WaitlistRepository waitlistRepository, SeatStateRepository seatRepository,
                             SeatInventoryService inventoryService, Outbox outbox, TransactionTemplate tx,
                             Clock clock) {
        this.waitlistRepository = waitlistRepository;
        this.seatRepository = seatRepository;
        this.inventoryService = inventoryService;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** Procesa la fila del viaje hasta que no quede a quién ofrecerle lo libre. Devuelve cuántas ofertas hizo. */
    public int procesar(UUID itineraryId) {
        if (!waitlistRepository.existsByItineraryIdAndEstado(itineraryId, WaitlistStatus.ESPERANDO)) {
            return 0;
        }
        int ofertas = 0;
        int fallos = 0;
        while (fallos < MAX_INTENTOS) {
            try {
                if (!Boolean.TRUE.equals(tx.execute(status -> ofrecerAlSiguiente(itineraryId)))) {
                    return ofertas;
                }
                ofertas++;
            } catch (ApiException e) {
                // Otro pasajero tomó (o está tomando) un asiento libre: se vuelve a leer lo que quede libre.
                fallos++;
                log.info("Lista de espera del viaje {}: no se pudo ofrecer ({}), intento {} de {}", itineraryId,
                        e.getCode(), fallos, MAX_INTENTOS);
            }
        }
        return ofertas;
    }

    /** Una oferta: al primero (en orden de llegada) cuya cantidad cabe en lo disponible. */
    private boolean ofrecerAlSiguiente(UUID itineraryId) {
        lockFila(itineraryId);
        KnownItinerary itinerary = inventoryService.ensureItinerary(itineraryId);
        Instant now = clock.instant();
        if (itinerary.isCancelado()) {
            cerrar(itineraryId, WaitlistStatus.ACTIVOS);
            return false;
        }
        if (cerradaPorSalida(itinerary, now)) {
            cerrar(itineraryId, List.of(WaitlistStatus.ESPERANDO));
            return false;
        }
        List<String> libres = new ArrayList<>(seatRepository.findSeatNumbers(itineraryId, SeatStatus.DISPONIBLE));
        Optional<WaitlistEntry> turno = waitlistRepository.lockFila(itineraryId, List.of(WaitlistStatus.ESPERANDO))
                .stream().filter(w -> w.getCantidad() <= libres.size()).findFirst();
        if (turno.isEmpty()) {
            return false;
        }
        WaitlistEntry entry = turno.get();
        libres.sort(SeatOrder.NATURAL);
        HoldResponse hold = inventoryService.holdSeats(itineraryId, libres.subList(0, entry.getCantidad()),
                entry.getUserId(), null);
        entry.ofrecer(hold.holdId(), now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", entry.getUserId());
        payload.put("itineraryId", itineraryId);
        payload.put("holdId", hold.holdId());
        payload.put("seatNumbers", hold.seatNumbers());
        payload.put("expiraEn", hold.expiraEn());
        outbox.add(InventoryEvents.WAITLIST_SEAT_OFFERED, payload);
        log.info("Lista de espera del viaje {}: {} asiento(s) ofrecidos al turno {}", itineraryId,
                entry.getCantidad(), entry.getId());
        return true;
    }

    /** ItineraryCancelled: la fila se cierra (también los turnos OFRECIDO, cuya retención se cancela). */
    public void onItineraryCancelled(UUID itineraryId) {
        tx.executeWithoutResult(status -> {
            lockFila(itineraryId);
            cerrar(itineraryId, WaitlistStatus.ACTIVOS);
        });
    }

    /**
     * 30 minutos antes de la salida (o con el viaje ya cancelado) se cierran los turnos que siguen ESPERANDO.
     * Los OFRECIDO conservan su retención hasta que se pague o venza. Devuelve cuántos viajes cerró.
     */
    public int cerrarProximasASalir() {
        List<UUID> viajes = waitlistRepository.findItineraryIdsACerrar(clock.instant().plus(CIERRE_ANTES_DE_SALIDA));
        for (UUID itineraryId : viajes) {
            procesar(itineraryId);
        }
        return viajes.size();
    }

    /** Viajes con turnos ESPERANDO (el barrido periódico los vuelve a procesar por si algo se quedó sin oferta). */
    public List<UUID> viajesEnEspera() {
        return waitlistRepository.findItineraryIdsByEstado(WaitlistStatus.ESPERANDO);
    }

    static boolean cerradaPorSalida(KnownItinerary itinerary, Instant now) {
        return itinerary.getFechaSalida() != null
                && !now.isBefore(itinerary.getFechaSalida().minus(CIERRE_ANTES_DE_SALIDA));
    }

    private void lockFila(UUID itineraryId) {
        waitlistRepository.advisoryLock(NAMESPACE, itineraryId.hashCode());
    }

    /** Pasa a CANCELADO los turnos en esos estados y publica WaitlistClosed con sus pasajeros. Requiere el lock. */
    private void cerrar(UUID itineraryId, Collection<WaitlistStatus> estados) {
        Instant now = clock.instant();
        List<UUID> userIds = new ArrayList<>();
        for (WaitlistEntry entry : waitlistRepository.lockFila(itineraryId, estados)) {
            entry.cancelar(now);
            userIds.add(entry.getUserId());
        }
        if (userIds.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", itineraryId);
        payload.put("userIds", userIds);
        outbox.add(InventoryEvents.WAITLIST_CLOSED, payload);
        log.info("Lista de espera del viaje {} cerrada: {} turno(s) cancelados", itineraryId, userIds.size());
    }
}
