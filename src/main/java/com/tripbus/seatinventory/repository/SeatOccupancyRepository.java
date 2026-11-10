package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.SeatOccupancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** US-142: ocupaciones de asientos por tramo. Las consultas de cruce usan el índice GiST de la restricción EXCLUDE. */
public interface SeatOccupancyRepository extends JpaRepository<SeatOccupancy, UUID> {

    List<SeatOccupancy> findByHoldId(UUID holdId);

    boolean existsByItineraryIdAndSeatNumber(UUID itineraryId, String seatNumber);

    /** Asientos (de los pedidos) que tienen alguna ocupación que se cruza con el tramo [desde, hasta). */
    @Query(value = """
            select distinct seat_number from seat_occupancies
            where itinerary_id = :itineraryId and seat_number in (:seats)
              and int4range(desde_orden, hasta_orden) && int4range(:desde, :hasta)
            """, nativeQuery = true)
    List<String> cruzados(@Param("itineraryId") UUID itineraryId, @Param("seats") Collection<String> seats,
                          @Param("desde") int desde, @Param("hasta") int hasta);

    /** Ocupaciones del viaje que se cruzan con el tramo (para el mapa de asientos por tramo). */
    @Query(value = """
            select * from seat_occupancies
            where itinerary_id = :itineraryId and int4range(desde_orden, hasta_orden) && int4range(:desde, :hasta)
            """, nativeQuery = true)
    List<SeatOccupancy> cruzanEn(@Param("itineraryId") UUID itineraryId, @Param("desde") int desde,
                                 @Param("hasta") int hasta);

    /** US-151/US-139: la ocupación VENDIDO del asiento que pertenece a alguna de estas retenciones (de la reserva). */
    @Query("""
            select o from SeatOccupancy o
            where o.itineraryId = :itineraryId and o.seatNumber = :seatNumber and o.holdId in :holdIds
              and o.estado = com.tripbus.seatinventory.domain.SeatStatus.VENDIDO
            """)
    Optional<SeatOccupancy> vendidaA(@Param("itineraryId") UUID itineraryId, @Param("seatNumber") String seatNumber,
                                     @Param("holdIds") Collection<UUID> holdIds);
}
