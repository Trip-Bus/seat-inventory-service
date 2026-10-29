package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.WaitlistEntry;
import com.tripbus.seatinventory.domain.WaitlistStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, UUID> {

    /**
     * US-140: la fila de un viaje se procesa de a una transacción a la vez. Lock de PostgreSQL que se suelta
     * solo al terminar la transacción; namespace separa estos locks de otros usos en la BD.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer advisoryLock(@Param("namespace") int namespace, @Param("key") int key);

    boolean existsByItineraryIdAndEstado(UUID itineraryId, WaitlistStatus estado);

    boolean existsByItineraryIdAndUserIdAndEstadoIn(UUID itineraryId, UUID userId,
                                                     Collection<WaitlistStatus> estados);

    /** Turnos en orden de llegada (el mismo orden que la posición que ve el pasajero). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select w from WaitlistEntry w
            where w.itineraryId = :itineraryId and w.estado in :estados
            order by w.creadoEn asc, w.id asc
            """)
    List<WaitlistEntry> lockFila(@Param("itineraryId") UUID itineraryId,
                                 @Param("estados") Collection<WaitlistStatus> estados);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select w from WaitlistEntry w
            where w.itineraryId = :itineraryId and w.userId = :userId and w.estado in :estados
            """)
    Optional<WaitlistEntry> lockActivo(@Param("itineraryId") UUID itineraryId, @Param("userId") UUID userId,
                                       @Param("estados") Collection<WaitlistStatus> estados);

    List<WaitlistEntry> findByUserIdOrderByCreadoEnDesc(UUID userId);

    /** Posición (desde 1) de un turno ESPERANDO: cuántos ESPERANDO llegaron antes que él, incluido él. */
    @Query(value = """
            select count(*) from waitlist
            where itinerary_id = :itineraryId and estado = 'ESPERANDO' and (creado_en, id) <= (:creadoEn, :id)
            """, nativeQuery = true)
    long posicion(@Param("itineraryId") UUID itineraryId, @Param("creadoEn") Instant creadoEn,
                  @Param("id") UUID id);

    /** Cierra el turno OFRECIDO de una retención que se vendió, venció, se liberó o se canceló. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update WaitlistEntry w set w.estado = :nuevo, w.actualizadoEn = :now
            where w.holdId = :holdId and w.estado = com.tripbus.seatinventory.domain.WaitlistStatus.OFRECIDO
            """)
    int cerrarOfrecido(@Param("holdId") UUID holdId, @Param("nuevo") WaitlistStatus nuevo,
                       @Param("now") Instant now);

    @Query("select distinct w.itineraryId from WaitlistEntry w where w.estado = :estado")
    List<UUID> findItineraryIdsByEstado(@Param("estado") WaitlistStatus estado);

    /** Viajes con turnos ESPERANDO cuya fila ya debe cerrarse: cancelados o que salen antes de limite. */
    @Query("""
            select distinct w.itineraryId from WaitlistEntry w, KnownItinerary i
            where i.id = w.itineraryId
              and w.estado = com.tripbus.seatinventory.domain.WaitlistStatus.ESPERANDO
              and (i.cancelado = true or i.fechaSalida <= :limite)
            """)
    List<UUID> findItineraryIdsACerrar(@Param("limite") Instant limite);
}
