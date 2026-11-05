package com.tripbus.seatinventory.domain;

import com.tripbus.seatinventory.service.DistributedSeatLock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-141 y US-142: reglas de los tramos, las categorías y el estado TRAMO, sin base de datos. */
class SegmentDomainTest {

    private static final Instant AHORA = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void dosTramosSeCruzanSiCompartenUnTrayecto() {
        Tramo unoATres = new Tramo(1, 3);
        assertThat(unoATres.cruza(new Tramo(2, 4))).isTrue();
        assertThat(unoATres.cruza(new Tramo(3, 5))).isFalse();
        assertThat(new Tramo(3, 5).cruza(unoATres)).isFalse();
        assertThat(Tramo.COMPLETO.cruza(new Tramo(100, 101))).isTrue();
        assertThat(unoATres.esCompleto()).isFalse();
        assertThat(Tramo.de(null, null)).isEqualTo(Tramo.COMPLETO);
        assertThat(Tramo.de(2, 5)).isEqualTo(new Tramo(2, 5));
    }

    @Test
    void unTramoMalFormadoEsUn400() {
        assertThatThrownBy(() -> new Tramo(0, 2)).extracting("code").isEqualTo("INVALID_SEGMENT");
        assertThatThrownBy(() -> new Tramo(4, 4)).extracting("code").isEqualTo("INVALID_SEGMENT");
        assertThatThrownBy(() -> new Tramo(1, Tramo.FIN + 1)).extracting("code").isEqualTo("INVALID_SEGMENT");
        assertThatThrownBy(() -> Tramo.de(null, 3)).extracting("code").isEqualTo("INVALID_SEGMENT");
    }

    @Test
    void laCategoriaSeNormalizaYSinEllaEsEstandar() {
        assertThat(new SeatSpec(" 12 ", " vip ").numero()).isEqualTo("12");
        assertThat(new SeatSpec("1", "vip").categoria()).isEqualTo("VIP");
        assertThat(new SeatSpec("1", "  ").categoriaONueva()).isEqualTo(SeatSpec.ESTANDAR);
        assertThat(new SeatSpec("1", "con espacio").categoria()).isNull();
        assertThat(new SeatSpec("1", "X".repeat(31)).categoria()).isNull();
        assertThat(SeatSpec.sinCategoria(null).numero()).isEmpty();
    }

    @Test
    void elAsientoPasaATramoYVuelveConSuCategoria() {
        SeatState seat = new SeatState(UUID.randomUUID(), UUID.randomUUID(), new SeatSpec("1", "CAMA"), AHORA);
        assertThat(seat.getCategoria()).isEqualTo("CAMA");
        seat.cambiarCategoria(null);
        assertThat(seat.getCategoria()).isEqualTo("CAMA");
        seat.cambiarCategoria("VIP");
        assertThat(seat.getCategoria()).isEqualTo("VIP");

        assertThat(seat.admiteTramos()).isTrue();
        seat.ocuparTramo(AHORA);
        assertThat(seat.getEstado()).isEqualTo(SeatStatus.TRAMO);
        assertThat(seat.admiteTramos()).isTrue();
        seat.liberarTramos(AHORA);
        assertThat(seat.getEstado()).isEqualTo(SeatStatus.DISPONIBLE);
        seat.bloquear("roto", AHORA);
        assertThat(seat.admiteTramos()).isFalse();
    }

    @Test
    void laRetencionYSuOcupacionLlevanElTramo() {
        Hold hold = new Hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("1"), AHORA,
                Duration.ofMinutes(10));
        assertThat(hold.esCompleta()).isTrue();
        hold.enTramo(new Tramo(2, 6));
        assertThat(hold.getTramo()).isEqualTo(new Tramo(2, 6));

        SeatOccupancy ocupacion = new SeatOccupancy(hold, "1", AHORA);
        assertThat(ocupacion.getTramo()).isEqualTo(new Tramo(2, 6));
        assertThat(ocupacion.getEstado()).isEqualTo(SeatStatus.RETENIDO);
        ocupacion.vender();
        ocupacion.moverA("3");
        assertThat(ocupacion.getEstado()).isEqualTo(SeatStatus.VENDIDO);
        assertThat(ocupacion.getSeatNumber()).isEqualTo("3");
        assertThat(ocupacion.getId()).isNotNull();

        WaitlistEntry entry = new WaitlistEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, AHORA);
        assertThat(entry.getTramo()).isEqualTo(Tramo.COMPLETO);
        assertThat(entry.enTramo(new Tramo(1, 2)).getTramo()).isEqualTo(new Tramo(1, 2));
    }

    @Test
    void elLockDeRedisDeUnTramoEsDistintoDelDelRecorridoCompleto() {
        UUID id = UUID.randomUUID();
        assertThat(DistributedSeatLock.key(id, "1", Tramo.COMPLETO)).isEqualTo(DistributedSeatLock.key(id, "1"));
        assertThat(DistributedSeatLock.key(id, "1", new Tramo(2, 5))).endsWith(":asiento:1:tramo:2-5");
    }
}
