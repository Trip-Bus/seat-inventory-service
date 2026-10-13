-- US-031: outbox transaccional. Cada evento de dominio se guarda en la MISMA transacción que el
-- cambio del asiento/retención; OutboxRelay lo publica después en el stream de Redis. Si Redis
-- falla, el evento queda pendiente (publicado_en NULL) y sale cuando Redis vuelve.
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    payload       JSONB NOT NULL,
    creado_en     TIMESTAMP WITH TIME ZONE NOT NULL,
    publicado_en  TIMESTAMP WITH TIME ZONE
);

-- Solo los pendientes: el relay los recorre en orden de creación.
CREATE INDEX idx_outbox_pendientes ON outbox (creado_en) WHERE publicado_en IS NULL;
