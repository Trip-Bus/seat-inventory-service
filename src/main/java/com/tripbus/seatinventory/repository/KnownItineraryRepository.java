package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.KnownItinerary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KnownItineraryRepository extends JpaRepository<KnownItinerary, UUID> {

    /** US-119: dos cambios de bus del mismo viaje no se aplican a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from KnownItinerary i where i.id = :id")
    Optional<KnownItinerary> lockById(@Param("id") UUID id);
}
