package com.tripbus.seatinventory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatOrderTest {

    @Test
    void ordenaNumericamenteYEliminaDuplicados() {
        assertThat(SeatOrder.sortedDistinct(List.of("10", "2", " 2", "1", "A3")))
                .containsExactly("1", "2", "10", "A3");
    }

    @Test
    void lasLlavesDeLockUsanHashTagPorItinerario() {
        var id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(DistributedSeatLock.key(id, "7"))
                .isEqualTo("lock:{itinerario:00000000-0000-0000-0000-000000000001}:asiento:7");
    }
}
