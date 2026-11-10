package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.SeatState;
import com.tripbus.seatinventory.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatStateRepository extends JpaRepository<SeatState, UUID> {

    List<SeatState> findByItineraryId(UUID itineraryId);

    Optional<SeatState> findByItineraryIdAndSeatNumber(UUID itineraryId, String seatNumber);

    /**
     * Tercera línea de defensa (US-026): SELECT ... FOR UPDATE en orden determinista
     * (por número de asiento) para que dos transacciones nunca se bloqueen mutuamente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from SeatState s
            where s.itineraryId = :itineraryId and s.seatNumber in :seatNumbers
            order by s.seatNumber asc
            """)
    List<SeatState> lockSeats(@Param("itineraryId") UUID itineraryId,
                              @Param("seatNumbers") Collection<String> seatNumbers);

    /** US-119: todos los asientos del viaje, en el mismo orden determinista que {@link #lockSeats}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatState s where s.itineraryId = :itineraryId order by s.seatNumber asc")
    List<SeatState> lockByItinerary(@Param("itineraryId") UUID itineraryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatState s where s.holdId = :holdId order by s.seatNumber asc")
    List<SeatState> lockByHold(@Param("holdId") UUID holdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatState s where s.offerId = :offerId order by s.itineraryId, s.seatNumber asc")
    List<SeatState> lockByOffer(@Param("offerId") UUID offerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatState s where s.itineraryId = :itineraryId and s.estado in :estados order by s.seatNumber asc")
    List<SeatState> lockByItineraryAndEstados(@Param("itineraryId") UUID itineraryId,
                                              @Param("estados") Collection<SeatStatus> estados);

    interface AvailabilityRow {
        UUID getItineraryId();

        SeatStatus getEstado();

        long getTotal();
    }

    @Query("""
            select s.itineraryId as itineraryId, s.estado as estado, count(s) as total
            from SeatState s
            where s.itineraryId in :itineraryIds
            group by s.itineraryId, s.estado
            """)
    List<AvailabilityRow> countByEstado(@Param("itineraryIds") Collection<UUID> itineraryIds);

    long countByItineraryIdAndEstado(UUID itineraryId, SeatStatus estado);

    /**
     * US-142: asientos libres en el tramo [desde, hasta): DISPONIBLE o TRAMO y sin ocupaciones que lo crucen.
     * Un asiento EN_OFERTA o BLOQUEADO no se vende por tramos.
     */
    @Query(value = """
            select s.seat_number from seat_states s
            where s.itinerary_id = :itineraryId and s.estado in ('DISPONIBLE', 'TRAMO')
              and not exists (select 1 from seat_occupancies o
                              where o.itinerary_id = s.itinerary_id and o.seat_number = s.seat_number
                                and int4range(o.desde_orden, o.hasta_orden) && int4range(:desde, :hasta))
            """, nativeQuery = true)
    List<String> libresEnTramo(@Param("itineraryId") UUID itineraryId, @Param("desde") int desde,
                               @Param("hasta") int hasta);

    interface TramoAvailabilityRow {
        UUID getItineraryId();

        long getDisponibles();

        long getTotal();
    }

    /** US-142: disponibilidad por tramo de varios viajes, agregada (total sin los BLOQUEADO, como siempre). */
    @Query(value = """
            select s.itinerary_id as itineraryId,
                   count(*) filter (where s.estado in ('DISPONIBLE', 'TRAMO')
                       and not exists (select 1 from seat_occupancies o
                                       where o.itinerary_id = s.itinerary_id and o.seat_number = s.seat_number
                                         and int4range(o.desde_orden, o.hasta_orden) && int4range(:desde, :hasta)))
                       as disponibles,
                   count(*) filter (where s.estado <> 'BLOQUEADO') as total
            from seat_states s
            where s.itinerary_id in (:itineraryIds)
            group by s.itinerary_id
            """, nativeQuery = true)
    List<TramoAvailabilityRow> disponibilidadEnTramo(@Param("itineraryIds") Collection<UUID> itineraryIds,
                                                     @Param("desde") int desde, @Param("hasta") int hasta);

    interface OccupancyCountRow {
        UUID getItineraryId();

        String getEstado();

        long getTotal();
    }

    /** US-191 + HT-08: asientos por viaje y estado, de los viajes vivos y de los archivados. */
    @Query(value = """
            select itinerary_id as itineraryId, estado, count(*) as total from (
                select itinerary_id, estado from seat_states where itinerary_id in (:itineraryIds)
                union all
                select itinerary_id, estado from seat_states_archive where itinerary_id in (:itineraryIds)
            ) t group by itinerary_id, estado
            """, nativeQuery = true)
    List<OccupancyCountRow> contarConArchivo(@Param("itineraryIds") Collection<UUID> itineraryIds);

    /** US-140: números de los asientos en un estado (sin cargar las entidades). */
    @Query("select s.seatNumber from SeatState s where s.itineraryId = :itineraryId and s.estado = :estado")
    List<String> findSeatNumbers(@Param("itineraryId") UUID itineraryId, @Param("estado") SeatStatus estado);
}
