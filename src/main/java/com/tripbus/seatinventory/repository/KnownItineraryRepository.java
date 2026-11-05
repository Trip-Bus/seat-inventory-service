package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.KnownItinerary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnownItineraryRepository extends JpaRepository<KnownItinerary, UUID> {

    /** US-119: dos cambios de bus del mismo viaje no se aplican a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from KnownItinerary i where i.id = :id")
    Optional<KnownItinerary> lockById(@Param("id") UUID id);

    /** HT-08: viajes archivados (solo lectura: los reportes de ocupación los siguen pidiendo). */
    @Query(value = """
            select id, capacidad, fecha_salida, cancelado, company_id from itineraries_archive where id in (:ids)
            """, nativeQuery = true)
    List<KnownItinerary> findArchived(@Param("ids") Collection<UUID> ids);

    @Query(value = "select count(*) > 0 from itineraries_archive where id = :id", nativeQuery = true)
    boolean isArchived(@Param("id") UUID id);
}
