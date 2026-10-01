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

    @Query("select h.id from Hold h where h.itineraryId = :itineraryId and h.estado = :estado")
    List<UUID> findIdsByItineraryIdAndEstado(@Param("itineraryId") UUID itineraryId,
                                             @Param("estado") HoldStatus estado);
}
