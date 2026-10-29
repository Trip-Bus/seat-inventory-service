package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.domain.HoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> lockById(@Param("id") UUID id);

    @Query("select h.id from Hold h where h.estado = :estado and h.expiraEn <= :now order by h.expiraEn asc")
    List<UUID> findIdsByEstadoAndExpiraEnBefore(@Param("estado") HoldStatus estado,
                                                @Param("now") Instant now,
                                                Pageable pageable);

    /** Retenciones confirmadas de una reserva en un viaje, bloqueadas en orden por id (US-151/US-139). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.itineraryId = :itineraryId and h.bookingId = :bookingId order by h.id asc")
    List<Hold> lockByItineraryAndBooking(@Param("itineraryId") UUID itineraryId,
                                         @Param("bookingId") UUID bookingId);

    @Query("select h.id from Hold h where h.itineraryId = :itineraryId and h.estado = :estado")
    List<UUID> findIdsByItineraryIdAndEstado(@Param("itineraryId") UUID itineraryId,
                                             @Param("estado") HoldStatus estado);

    interface HoldCountRow {
        UUID getItineraryId();

        HoldStatus getEstado();

        long getTotal();

        long getPorUsuario();
    }

    /** US-191: retenciones por viaje y estado; porUsuario cuenta las que el pasajero liberó él mismo. */
    @Query("""
            select h.itineraryId as itineraryId, h.estado as estado, count(h) as total,
                   sum(case when h.motivoCierre = :porUsuario then 1 else 0 end) as porUsuario
            from Hold h
            where h.itineraryId in :itineraryIds
            group by h.itineraryId, h.estado
            """)
    List<HoldCountRow> countByEstado(@Param("itineraryIds") Collection<UUID> itineraryIds,
                                     @Param("porUsuario") String porUsuario);
}
