package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.SeatAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface SeatAuditLogRepository extends JpaRepository<SeatAuditLog, Long> {

    /**
     * ¿Alguna de estas retenciones ya dejó este asiento por una anulación (origen VOID_*) o un cambio
     * (SWAP)? Es lo que hace idempotentes a US-151 y US-139: el asiento ya no tiene dueño, pero la
     * auditoría guarda de qué retención salió. holdIds no puede ir vacío.
     */
    @Query("""
            select count(a) > 0 from SeatAuditLog a
            where a.itineraryId = :itineraryId and a.seatNumber = :seatNumber
              and a.holdId in :holdIds and a.origen like :origen
            """)
    boolean yaLiberadoPor(@Param("itineraryId") UUID itineraryId,
                          @Param("seatNumber") String seatNumber,
                          @Param("holdIds") Collection<UUID> holdIds,
                          @Param("origen") String origen);
}
