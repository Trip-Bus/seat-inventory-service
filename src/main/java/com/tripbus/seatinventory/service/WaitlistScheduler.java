package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.redis.LeaderLock;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * US-140: cuándo se procesa la lista de espera.
 * <ul>
 *   <li>Cuando un asiento vuelve a DISPONIBLE ({@link AsientoLiberado}), después del commit y en otro hilo: la
 *       liberación no espera a la fila ni retiene su conexión mientras tanto. Varias liberaciones del mismo
 *       viaje que llegan antes de que empiece el procesamiento se juntan en una sola pasada.</li>
 *   <li>Un barrido periódico (en un solo nodo) que cierra las filas de los viajes que salen en menos de 30 min
 *       y vuelve a procesar las filas con turnos esperando, por si alguna liberación no alcanzó a procesarse.</li>
 * </ul>
 */
@Component
public class WaitlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitlistScheduler.class);

    private final WaitlistProcessor processor;
    private final LeaderLock leaderLock;
    private final ExecutorService executor;
    private final Set<UUID> pendientes = ConcurrentHashMap.newKeySet();

    @Autowired
    public WaitlistScheduler(WaitlistProcessor processor, LeaderLock leaderLock) {
        this(processor, leaderLock, Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("waitlist-", 0).daemon(true).factory()));
    }

    WaitlistScheduler(WaitlistProcessor processor, LeaderLock leaderLock, ExecutorService executor) {
        this.processor = processor;
        this.leaderLock = leaderLock;
        this.executor = executor;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAsientoLiberado(AsientoLiberado evento) {
        programar(evento.itineraryId());
    }

    /** Encola el procesamiento del viaje, salvo que ya esté encolado y sin empezar. */
    void programar(UUID itineraryId) {
        if (!pendientes.add(itineraryId)) {
            return;
        }
        try {
            executor.execute(() -> {
                pendientes.remove(itineraryId);
                procesarSinFallar(itineraryId);
            });
        } catch (RejectedExecutionException e) {
            pendientes.remove(itineraryId);
            log.warn("No se pudo encolar la lista de espera del viaje {}: {}", itineraryId, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.waitlist-interval-ms:30000}", initialDelay = 10000)
    public void barrer() {
        leaderLock.runIfLeader("seat-inventory-waitlist", Duration.ofSeconds(25), this::revisarFilas);
    }

    /** Cierra las filas que ya deben cerrarse y procesa las que tienen turnos esperando. */
    public void revisarFilas() {
        processor.cerrarProximasASalir();
        processor.viajesEnEspera().forEach(this::procesarSinFallar);
    }

    private void procesarSinFallar(UUID itineraryId) {
        try {
            processor.procesar(itineraryId);
        } catch (RuntimeException e) {
            log.warn("No se pudo procesar la lista de espera del viaje {}: {}", itineraryId, e.getMessage());
        }
    }

    @PreDestroy
    void detener() {
        executor.shutdownNow();
    }
}
