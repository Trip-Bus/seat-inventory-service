-- Inventario de asientos (esquema propio del servicio).

CREATE TABLE seat_states (
    id             UUID PRIMARY KEY,
    itinerary_id   UUID NOT NULL,
    seat_number    VARCHAR(10) NOT NULL,
    estado         VARCHAR(20) NOT NULL,
    user_id        UUID,
    hold_id        UUID,
    offer_id       UUID,
    vendido_en     TIMESTAMP WITH TIME ZONE,
    version        BIGINT NOT NULL DEFAULT 0,
    actualizado_en TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- US-025, segunda línea de defensa: un asiento existe UNA sola vez por itinerario.
    CONSTRAINT uq_seat_states_itinerary_seat UNIQUE (itinerary_id, seat_number),
    CONSTRAINT ck_seat_states_estado CHECK (estado IN ('DISPONIBLE', 'RETENIDO', 'VENDIDO', 'EN_OFERTA')),
    -- Un asiento vendido siempre tiene comprador y fecha; uno retenido siempre tiene retención.
    CONSTRAINT ck_seat_states_vendido CHECK (estado <> 'VENDIDO' OR (user_id IS NOT NULL AND vendido_en IS NOT NULL)),
    CONSTRAINT ck_seat_states_retenido CHECK (estado <> 'RETENIDO' OR hold_id IS NOT NULL)
);

CREATE INDEX idx_seat_states_hold ON seat_states (hold_id);
CREATE INDEX idx_seat_states_offer ON seat_states (offer_id);

CREATE TABLE holds (
    id            UUID PRIMARY KEY,
    itinerary_id  UUID NOT NULL,
    user_id       UUID NOT NULL,
    offer_id      UUID,
    booking_id    UUID,
    creado_en     TIMESTAMP WITH TIME ZONE NOT NULL,
    expira_en     TIMESTAMP WITH TIME ZONE NOT NULL,
    extendido     BOOLEAN NOT NULL DEFAULT FALSE,
    estado        VARCHAR(20) NOT NULL,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_holds_estado CHECK (estado IN ('ACTIVA', 'CONFIRMADA', 'LIBERADA', 'EXPIRADA', 'CANCELADA'))
);

CREATE INDEX idx_holds_estado_expira ON holds (estado, expira_en);

CREATE TABLE hold_seats (
    hold_id      UUID NOT NULL REFERENCES holds (id),
    seat_number  VARCHAR(10) NOT NULL,
    PRIMARY KEY (hold_id, seat_number)
);

CREATE TABLE seat_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    itinerary_id     UUID NOT NULL,
    seat_number      VARCHAR(10) NOT NULL,
    estado_anterior  VARCHAR(20),
    estado_nuevo     VARCHAR(20) NOT NULL,
    user_id          UUID,
    hold_id          UUID,
    origen           VARCHAR(40) NOT NULL,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_seat_audit_itinerary ON seat_audit_log (itinerary_id, seat_number);

-- Itinerarios conocidos (creados por el evento ItineraryPublished).
CREATE TABLE itineraries (
    id            UUID PRIMARY KEY,
    capacidad     INTEGER NOT NULL,
    fecha_salida  TIMESTAMP WITH TIME ZONE,
    cancelado     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE processed_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
