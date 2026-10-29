package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.redis.LeaderLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaitlistSchedulerTest {

    private final WaitlistProcessor processor = mock(WaitlistProcessor.class);
    private final LeaderLock leaderLock = mock(LeaderLock.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final WaitlistScheduler scheduler = new WaitlistScheduler(processor, leaderLock, executor);
    private final UUID itineraryId = UUID.randomUUID();

    @Test
    void variasLiberacionesDelMismoViajeSeJuntanHastaQueEmpiezaElProcesamiento() {
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        ArgumentCaptor<Runnable> tarea = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(tarea.capture());

        tarea.getValue().run();
        verify(processor).procesar(itineraryId);
        // Ya empezó: una liberación nueva vuelve a encolar.
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        verify(executor, times(2)).execute(any());
    }

    @Test
    void siElEjecutorRechazaLaTareaSePuedeVolverAEncolar() {
        doThrow(new RejectedExecutionException("cerrado")).when(executor).execute(any());
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        verify(executor, times(2)).execute(any());
    }

    @Test
    void unErrorAlProcesarNoSePropaga() {
        when(processor.procesar(itineraryId)).thenThrow(new IllegalStateException("BD caída"));
        scheduler.onAsientoLiberado(new AsientoLiberado(itineraryId));
        ArgumentCaptor<Runnable> tarea = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(tarea.capture());
        assertThatCode(() -> tarea.getValue().run()).doesNotThrowAnyException();
    }

    @Test
    void elBarridoCorreEnUnSoloNodoYCierraYProcesaLasFilas() {
        scheduler.barrer();
        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(leaderLock).runIfLeader(eq("seat-inventory-waitlist"), any(Duration.class), job.capture());

        UUID otro = UUID.randomUUID();
        when(processor.viajesEnEspera()).thenReturn(List.of(itineraryId, otro));
        job.getValue().run();
        verify(processor).cerrarProximasASalir();
        verify(processor).procesar(itineraryId);
        verify(processor).procesar(otro);
    }

    @Test
    void alApagarseDetieneElEjecutor() {
        scheduler.detener();
        verify(executor).shutdownNow();
        assertThatCode(() -> new WaitlistScheduler(processor, leaderLock).detener()).doesNotThrowAnyException();
    }
}
