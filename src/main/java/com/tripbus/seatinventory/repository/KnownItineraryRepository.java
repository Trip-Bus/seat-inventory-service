package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.KnownItinerary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnownItineraryRepository extends JpaRepository<KnownItinerary, UUID> {
}
