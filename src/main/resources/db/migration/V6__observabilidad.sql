-- HT-10: contexto W3C de la traza (traceparent) con el que se generó cada evento del outbox; el relay lo
-- publica con el evento para que el consumidor siga la misma traza. NULL si no había traza.
ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(128);
