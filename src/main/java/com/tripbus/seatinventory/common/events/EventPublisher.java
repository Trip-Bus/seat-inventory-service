package com.tripbus.seatinventory.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publica eventos de dominio en el stream de Redis (XADD). Bus de eventos del MVP.
 * Los servicios no lo llaman directamente: escriben en el {@link Outbox} y el
 * {@link OutboxRelay} publica desde ahí (US-031, ningún evento se pierde si Redis falla).
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final int TRIM_EVERY = 500;

    private final StringRedisTemplate redis;
    private final EventProperties properties;
    private final String source;
    private final AtomicLong published = new AtomicLong();

    public EventPublisher(
            StringRedisTemplate redis,
            EventProperties properties,
            @Value("${spring.application.name}") String source) {
        this.redis = redis;
        this.properties = properties;
        this.source = source;
    }

    /**
     * Publica de inmediato un evento cuyo payload ya es JSON. Lanza excepción si Redis no está
     * disponible (el relay lo reintenta).
     */
    public void publish(String eventId, String type, Instant occurredAt, String payloadJson) {
        if (!properties.enabled()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("type", type);
        fields.put("source", source);
        fields.put("occurredAt", occurredAt.toString());
        fields.put("payload", payloadJson);

        MapRecord<String, String, String> record = StreamRecords.newRecord().in(properties.stream()).ofMap(fields);
        redis.opsForStream().add(record);
        if (published.incrementAndGet() % TRIM_EVERY == 0) {
            redis.opsForStream().trim(properties.stream(), properties.maxLen(), true);
        }
        log.debug("Evento publicado {} {}", type, eventId);
    }
}
