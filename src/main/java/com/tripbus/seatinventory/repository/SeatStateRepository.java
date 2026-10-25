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
}
