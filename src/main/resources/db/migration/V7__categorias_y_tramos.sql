-- Sprint 6.
--   US-141: categoría de cada asiento (la manda catalog en ItineraryPublished y en el cambio de bus).
--   US-142: venta por tramos. Una retención o venta ocupa un asiento en un tramo [desde_orden, hasta_orden) de
--           las paradas; el recorrido completo es [1, 32767).

-- ------------------------------------------------------------------ US-141
ALTER TABLE seat_states ADD COLUMN categoria VARCHAR(30) NOT NULL DEFAULT 'ESTANDAR';

-- ------------------------------------------------------------------ US-142
-- btree_gist: la restricción EXCLUDE combina igualdad (uuid, varchar) con el cruce de rangos (&&).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- TRAMO: el asiento tiene ocupaciones solo en parte del recorrido. No está libre para el recorrido completo,
-- pero sí para los tramos que no se crucen con ellas (seat_occupancies dice cuáles).
ALTER TABLE seat_states DROP CONSTRAINT ck_seat_states_estado;
ALTER TABLE seat_states ADD CONSTRAINT ck_seat_states_estado
    CHECK (estado IN ('DISPONIBLE', 'RETENIDO', 'VENDIDO', 'EN_OFERTA', 'BLOQUEADO', 'TRAMO'));

ALTER TABLE holds ADD COLUMN desde_orden INTEGER NOT NULL DEFAULT 1;
ALTER TABLE holds ADD COLUMN hasta_orden INTEGER NOT NULL DEFAULT 32767;
ALTER TABLE holds ADD CONSTRAINT ck_holds_tramo
    CHECK (desde_orden >= 1 AND desde_orden < hasta_orden AND hasta_orden <= 32767);

ALTER TABLE waitlist ADD COLUMN desde_orden INTEGER NOT NULL DEFAULT 1;
ALTER TABLE waitlist ADD COLUMN hasta_orden INTEGER NOT NULL DEFAULT 32767;
ALTER TABLE waitlist ADD CONSTRAINT ck_waitlist_tramo
    CHECK (desde_orden >= 1 AND desde_orden < hasta_orden AND hasta_orden <= 32767);

-- Cada retención y cada venta, completa o por tramo, ocupa aquí cada uno de sus asientos.
CREATE TABLE seat_occupancies (
    id            UUID PRIMARY KEY,
    itinerary_id  UUID NOT NULL,
    seat_number   VARCHAR(10) NOT NULL,
    desde_orden   INTEGER NOT NULL,
    hasta_orden   INTEGER NOT NULL,
    estado        VARCHAR(20) NOT NULL,
    hold_id       UUID NOT NULL,
    user_id       UUID NOT NULL,
    creado_en     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_seat_occupancies_estado CHECK (estado IN ('RETENIDO', 'VENDIDO')),
    CONSTRAINT ck_seat_occupancies_tramo
        CHECK (desde_orden >= 1 AND desde_orden < hasta_orden AND hasta_orden <= 32767),
    -- Tercera línea de defensa por tramos: dos ocupaciones del mismo asiento nunca se cruzan, aunque la
    -- aplicación falle. [a, b) y [b, c) no se cruzan (int4range es cerrado-abierto).
    CONSTRAINT ex_seat_occupancies_sin_cruce EXCLUDE USING gist (
        itinerary_id WITH =,
        seat_number WITH =,
        int4range(desde_orden, hasta_orden) WITH &&)
);

CREATE INDEX idx_seat_occupancies_hold ON seat_occupancies (hold_id);

-- Las retenciones y ventas que ya existían son del recorrido completo.
INSERT INTO seat_occupancies (id, itinerary_id, seat_number, desde_orden, hasta_orden, estado, hold_id, user_id,
                              creado_en)
SELECT gen_random_uuid(), itinerary_id, seat_number, 1, 32767, estado, hold_id, user_id, actualizado_en
FROM seat_states
WHERE estado IN ('RETENIDO', 'VENDIDO') AND hold_id IS NOT NULL AND user_id IS NOT NULL;

-- US-025 ampliado: un asiento VENDIDO del recorrido completo tampoco pasa a TRAMO (solo vuelve a DISPONIBLE).
CREATE OR REPLACE FUNCTION fn_seat_states_no_double_sale() RETURNS trigger AS $$
BEGIN
    IF OLD.estado = 'VENDIDO' THEN
        IF NEW.estado = 'VENDIDO'
           AND (NEW.hold_id IS DISTINCT FROM OLD.hold_id OR NEW.user_id IS DISTINCT FROM OLD.user_id) THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
        IF NEW.estado IN ('RETENIDO', 'EN_OFERTA', 'BLOQUEADO', 'TRAMO') THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
