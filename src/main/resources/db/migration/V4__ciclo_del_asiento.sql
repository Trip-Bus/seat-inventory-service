-- Sprint 3: ciclo del asiento.
--   US-112: nuevo estado BLOQUEADO (asientos que la empresa no vende) con su motivo.
--   US-112: itineraries.company_id (llega en ItineraryPublished); las filas viejas quedan en NULL.
--   US-151/US-139: la anulación (VENDIDO -> DISPONIBLE) ya la permitía el trigger de V2.

ALTER TABLE seat_states DROP CONSTRAINT ck_seat_states_estado;
ALTER TABLE seat_states ADD CONSTRAINT ck_seat_states_estado
    CHECK (estado IN ('DISPONIBLE', 'RETENIDO', 'VENDIDO', 'EN_OFERTA', 'BLOQUEADO'));

ALTER TABLE seat_states ADD COLUMN bloqueo_motivo VARCHAR(200);
-- Un asiento bloqueado siempre dice por qué; uno que no lo está no arrastra un motivo viejo.
ALTER TABLE seat_states ADD CONSTRAINT ck_seat_states_bloqueado
    CHECK ((estado = 'BLOQUEADO') = (bloqueo_motivo IS NOT NULL));

ALTER TABLE itineraries ADD COLUMN company_id UUID;

-- US-025 ampliado: un asiento VENDIDO tampoco puede pasar a BLOQUEADO.
CREATE OR REPLACE FUNCTION fn_seat_states_no_double_sale() RETURNS trigger AS $$
BEGIN
    IF OLD.estado = 'VENDIDO' THEN
        IF NEW.estado = 'VENDIDO'
           AND (NEW.hold_id IS DISTINCT FROM OLD.hold_id OR NEW.user_id IS DISTINCT FROM OLD.user_id) THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
        IF NEW.estado IN ('RETENIDO', 'EN_OFERTA', 'BLOQUEADO') THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
