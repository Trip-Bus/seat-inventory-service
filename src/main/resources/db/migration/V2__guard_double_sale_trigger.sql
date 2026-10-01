-- US-025: la base de datos IMPIDE vender dos veces un asiento, aunque la aplicación falle.
-- Un asiento VENDIDO no puede volver a venderse a otra retención/usuario ni volver a
-- retenerse u ofertarse. Solo puede volver a DISPONIBLE (anulación/reembolso, Sprint 2).
CREATE OR REPLACE FUNCTION fn_seat_states_no_double_sale() RETURNS trigger AS $$
BEGIN
    IF OLD.estado = 'VENDIDO' THEN
        IF NEW.estado = 'VENDIDO'
           AND (NEW.hold_id IS DISTINCT FROM OLD.hold_id OR NEW.user_id IS DISTINCT FROM OLD.user_id) THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
        IF NEW.estado IN ('RETENIDO', 'EN_OFERTA') THEN
            RAISE EXCEPTION 'DOUBLE_SALE: el asiento % del itinerario % ya fue vendido', OLD.seat_number, OLD.itinerary_id
                USING ERRCODE = '23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_seat_states_no_double_sale
    BEFORE UPDATE ON seat_states
    FOR EACH ROW
    EXECUTE FUNCTION fn_seat_states_no_double_sale();
