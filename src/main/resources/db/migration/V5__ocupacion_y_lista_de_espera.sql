-- Sprint 4.
--   US-191: ocupación y abandono de compra por viaje, con consultas agregadas sobre seat_states y holds.
--   US-140: lista de espera cuando el viaje está agotado.

-- US-191: por qué se cerró la retención (LIBERADA_POR_USUARIO, PAGO_RECHAZADO, EXPIRADA, ...). Con esto la
-- tasa de abandono separa lo que liberó el pasajero de lo que liberó el sistema. Las viejas quedan en NULL.
ALTER TABLE holds ADD COLUMN motivo_cierre VARCHAR(200);

-- US-191: los conteos agrupan por (itinerario, estado) sin recorrer toda la tabla.
CREATE INDEX idx_holds_itinerary_estado ON holds (itinerary_id, estado);
CREATE INDEX idx_seat_states_itinerary_estado ON seat_states (itinerary_id, estado);

-- US-140: turnos de la lista de espera de cada viaje, en orden de llegada (creado_en, id).
CREATE TABLE waitlist (
    id             UUID PRIMARY KEY,
    itinerary_id   UUID NOT NULL,
    user_id        UUID NOT NULL,
    cantidad       INTEGER NOT NULL,
    estado         VARCHAR(20) NOT NULL,
    hold_id        UUID,
    creado_en      TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_waitlist_cantidad CHECK (cantidad BETWEEN 1 AND 6),
    CONSTRAINT ck_waitlist_estado
        CHECK (estado IN ('ESPERANDO', 'OFRECIDO', 'CONVERTIDO', 'VENCIDO', 'CANCELADO')),
    -- Un turno ofrecido siempre tiene la retención que se le creó.
    CONSTRAINT ck_waitlist_ofrecido CHECK (estado <> 'OFRECIDO' OR hold_id IS NOT NULL)
);

-- Un único turno activo por pasajero y viaje (los cerrados se conservan como historial).
CREATE UNIQUE INDEX uq_waitlist_activo ON waitlist (itinerary_id, user_id)
    WHERE estado IN ('ESPERANDO', 'OFRECIDO');
CREATE INDEX idx_waitlist_fila ON waitlist (itinerary_id, estado, creado_en, id);
CREATE INDEX idx_waitlist_usuario ON waitlist (user_id, creado_en);
CREATE INDEX idx_waitlist_hold ON waitlist (hold_id) WHERE hold_id IS NOT NULL;
