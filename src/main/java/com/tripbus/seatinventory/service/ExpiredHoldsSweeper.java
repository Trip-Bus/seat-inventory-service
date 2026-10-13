package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.redis.LeaderLock;
import com.tripbus.seatinventory.domain.HoldStatus;
import com.tripbus.seatinventory.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * ReleaseExpiredHolds: job periódico de barrido de retenciones vencidas. Corre en un único
 * nodo por ventana gracias al {@link LeaderLock} (regla dura del doc). Si Redis cae, el
 * LeaderLock elige líder con un advisory lock de PostgreSQL y las retenciones siguen expirando.
 */
@Component
public class ExpiredHoldsSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredHoldsSweeper.class);
    private static final int BATCH = 200;

    private final HoldRepository holdRepository;
    private final SeatInventoryService inventoryService;
    private final LeaderLock leaderLock;
    private final Clock clock;

    public ExpiredHoldsSweeper(HoldRepository holdRepository, SeatInventoryService inventoryService,
                               LeaderLock leaderLock, Clock clock) {
        this.holdRepository = holdRepository;
        this.inventoryService = inventoryService;
        this.leaderLock = leaderLock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.sweep-interval-ms:5000}", initialDelay = 5000)
    public void sweep() {
        leaderLock.runIfLeader("seat-inventory-expired-holds", Duration.ofSeconds(4), this::releaseExpiredHolds);
    }

    /** Devuelve cuántas retenciones expiró. */
    public int releaseExpiredHolds() {
        List<UUID> vencidas = holdRepository.findIdsByEstadoAndExpiraEnBefore(
                HoldStatus.ACTIVA, clock.instant(), PageRequest.of(0, BATCH));
        int expiradas = 0;
        for (UUID holdId : vencidas) {
            try {
                if (inventoryService.expireHold(holdId)) {
                    expiradas++;
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo expirar la retención {}: {}", holdId, e.getMessage());
            }
        }
        if (expiradas > 0) {
            log.info("Retenciones expiradas: {}", expiradas);
        }
        return expiradas;
    }
}
