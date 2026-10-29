package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.HoldStatus;
import com.tripbus.seatinventory.domain.KnownItinerary;
import com.tripbus.seatinventory.domain.SeatStatus;
import com.tripbus.seatinventory.repository.HoldRepository;
import com.tripbus.seatinventory.repository.KnownItineraryRepository;
import com.tripbus.seatinventory.repository.SeatStateRepository;
import com.tripbus.seatinventory.web.dto.OccupancyResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * US-191: ocupación y abandono de compra por viaje. Las cifras salen de dos consultas agregadas (asientos y
 * retenciones agrupados por viaje y estado), sin cargar filas en memoria.
 */
@Service
public class OccupancyService {

    /** Tope de viajes por consulta interna (catalog y booking piden por páginas). */
    public static final int MAX_ITINERARIOS = 200;

    private final SeatStateRepository seatRepository;
    private final HoldRepository holdRepository;
    private final KnownItineraryRepository itineraryRepository;

    public OccupancyService(SeatStateRepository seatRepository, HoldRepository holdRepository,
                            KnownItineraryRepository itineraryRepository) {
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.itineraryRepository = itineraryRepository;
    }

    /**
     * Ocupación de un viaje para el OPERADOR de su empresa o un ADMIN. A cualquier otro (incluido el operador de
     * otra empresa) se le responde 404, como si el viaje no existiera.
     */
    @Transactional(readOnly = true)
    public OccupancyResponse ocupacion(UUID itineraryId, Actor actor) {
        KnownItinerary itinerary = itineraryRepository.findById(itineraryId)
                .filter(it -> actor != null && actor.puedeGestionar(it))
                .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
        return calcular(List.of(itinerary)).get(0);
    }

    /** Interno (catalog, booking): los mismos campos por viaje; los viajes desconocidos se omiten. */
    @Transactional(readOnly = true)
    public List<OccupancyResponse> ocupaciones(Collection<UUID> itineraryIds) {
        Set<UUID> ids = new LinkedHashSet<>(itineraryIds);
        if (ids.size() > MAX_ITINERARIOS) {
            throw ApiException.badRequest("TOO_MANY_ITINERARIES",
                    "Se pueden consultar hasta " + MAX_ITINERARIOS + " viajes a la vez");
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, KnownItinerary> conocidos = new HashMap<>();
        itineraryRepository.findAllById(ids).forEach(it -> conocidos.put(it.getId(), it));
        return calcular(ids.stream().filter(conocidos::containsKey).map(conocidos::get).toList());
    }

    private List<OccupancyResponse> calcular(List<KnownItinerary> itineraries) {
        if (itineraries.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = itineraries.stream().map(KnownItinerary::getId).toList();
        Map<UUID, Map<SeatStatus, Long>> asientos = new HashMap<>();
        for (SeatStateRepository.AvailabilityRow row : seatRepository.countByEstado(ids)) {
            asientos.computeIfAbsent(row.getItineraryId(), k -> new EnumMap<>(SeatStatus.class))
                    .put(row.getEstado(), row.getTotal());
        }
        Map<UUID, Map<HoldStatus, Long>> retenciones = new HashMap<>();
        Map<UUID, Long> liberadasPorUsuario = new HashMap<>();
        for (HoldRepository.HoldCountRow row : holdRepository.countByEstado(ids, Hold.LIBERADA_POR_USUARIO)) {
            retenciones.computeIfAbsent(row.getItineraryId(), k -> new EnumMap<>(HoldStatus.class))
                    .put(row.getEstado(), row.getTotal());
            if (row.getEstado() == HoldStatus.LIBERADA) {
                liberadasPorUsuario.put(row.getItineraryId(), row.getPorUsuario());
            }
        }
        return itineraries.stream().map(it -> respuesta(it,
                asientos.getOrDefault(it.getId(), Map.of()),
                retenciones.getOrDefault(it.getId(), Map.of()),
                liberadasPorUsuario.getOrDefault(it.getId(), 0L))).toList();
    }

    private static OccupancyResponse respuesta(KnownItinerary it, Map<SeatStatus, Long> asientos,
                                               Map<HoldStatus, Long> holds, long porUsuario) {
        long vendidos = asientos.getOrDefault(SeatStatus.VENDIDO, 0L);
        long creadas = holds.values().stream().mapToLong(Long::longValue).sum();
        long expiradas = holds.getOrDefault(HoldStatus.EXPIRADA, 0L);
        OccupancyResponse.Retenciones r = new OccupancyResponse.Retenciones(creadas,
                holds.getOrDefault(HoldStatus.ACTIVA, 0L), holds.getOrDefault(HoldStatus.CONFIRMADA, 0L), expiradas,
                holds.getOrDefault(HoldStatus.LIBERADA, 0L), porUsuario, holds.getOrDefault(HoldStatus.CANCELADA, 0L));
        return new OccupancyResponse(it.getId(), it.getCapacidad(),
                asientos.getOrDefault(SeatStatus.DISPONIBLE, 0L), asientos.getOrDefault(SeatStatus.RETENIDO, 0L),
                vendidos, asientos.getOrDefault(SeatStatus.EN_OFERTA, 0L),
                asientos.getOrDefault(SeatStatus.BLOQUEADO, 0L),
                redondear(it.getCapacidad() > 0 ? vendidos * 100.0 / it.getCapacidad() : 0, 2), r,
                // Sin contar lo que liberó el sistema (itinerario cancelado, pago rechazado).
                redondear(creadas > 0 ? (expiradas + porUsuario) / (double) creadas : 0, 4));
    }

    static double redondear(double valor, int decimales) {
        double factor = Math.pow(10, decimales);
        return Math.round(valor * factor) / factor;
    }
}
