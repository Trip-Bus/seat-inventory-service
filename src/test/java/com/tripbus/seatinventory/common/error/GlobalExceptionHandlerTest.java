package com.tripbus.seatinventory.common.error;

import jakarta.persistence.LockTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    static Stream<RuntimeException> bloqueos() {
        return Stream.of(
                new PessimisticLockingFailureException("could not obtain lock"),
                new CannotAcquireLockException("lock_not_available"),
                new LockTimeoutException("lock timeout"));
    }

    @ParameterizedTest
    @MethodSource("bloqueos")
    void unBloqueoNoObtenidoEs409SeatLockedConElFormatoDeError(RuntimeException bloqueo) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Lanza(bloqueo))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/lanza"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_LOCKED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.details").isMap());
    }

    @RestController
    static class Lanza {

        private final RuntimeException error;

        Lanza(RuntimeException error) {
            this.error = error;
        }

        @PostMapping("/lanza")
        void lanza() {
            throw error;
        }
    }
}
