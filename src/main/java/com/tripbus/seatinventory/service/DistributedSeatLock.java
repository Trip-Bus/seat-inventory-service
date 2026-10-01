package com.tripbus.seatinventory.service;

import com.tripbus.seatinventory.common.error.ApiException;
import com.tripbus.seatinventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * US-026, primera línea de defensa: lock distribuido en Redis por asiento.
 * <ul>
 *   <li>Llave {@code lock:{itinerario:<id>}:asiento:<n>} — el hash tag {@code {itinerario:<id>}}
 *       hace que en Redis Cluster todos los locks de un itinerario caigan en el mismo nodo.</li>
 *   <li>SET NX PX con TTL de 15 s y un token único: solo quien lo tomó puede liberarlo (Lua).</li>
 *   <li>Adquisición en orden ascendente (sin interbloqueos) y "fail fast": si no obtiene TODOS
 *       los locks en 500 ms libera los que tenía y responde conflicto.</li>
 * </ul>
 * Si Redis no está disponible el servicio sigue funcionando con las otras dos líneas de
 * defensa (SELECT ... FOR UPDATE y restricciones de la BD): nunca hay sobreventa.
 */
@Component
public class DistributedSeatLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedSeatLock.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final InventoryProperties properties;

    public DistributedSeatLock(StringRedisTemplate redis, InventoryProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public static String key(UUID itineraryId, String seatNumber) {
        return "lock:{itinerario:" + itineraryId + "}:asiento:" + seatNumber;
    }

    public SeatLocks acquireAll(UUID itineraryId, List<String> sortedSeats) {
        String token = UUID.randomUUID().toString();
        List<String> acquired = new ArrayList<>();
        long deadline = System.nanoTime() + properties.lockWait().toNanos();
        try {
            for (String seat : sortedSeats) {
                String key = key(itineraryId, seat);
                while (true) {
                    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, properties.lockTtl()))) {
                        acquired.add(key);
                        break;
                    }
                    if (System.nanoTime() >= deadline) {
                        release(acquired, token);
                        throw ApiException.conflict("SEAT_LOCKED",
                                "El asiento " + seat + " está siendo tomado por otro pasajero. Intenta de nuevo.");
                    }
                    sleep(5 + ThreadLocalRandom.current().nextInt(20));
                }
            }
            return new SeatLocks(this, acquired, token, true);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            release(acquired, token);
            log.warn("Redis no disponible para locks ({}); se continúa solo con los locks de la base de datos",
                    e.getMessage());
            return new SeatLocks(this, List.of(), token, false);
        }
    }

    void release(List<String> keys, String token) {
        for (String key : keys) {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(key), token);
            } catch (RuntimeException e) {
                log.warn("No se pudo liberar el lock {} (expirará solo en {}): {}", key, properties.lockTtl(),
                        e.getMessage());
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("SEAT_LOCKED", "Operación interrumpida");
        }
    }

    /** Locks adquiridos; se liberan al cerrar (try-with-resources). */
    public static final class SeatLocks implements AutoCloseable {

        private final DistributedSeatLock owner;
        private final List<String> keys;
        private final String token;
        private final boolean redisLocked;

        SeatLocks(DistributedSeatLock owner, List<String> keys, String token, boolean redisLocked) {
            this.owner = owner;
            this.keys = keys;
            this.token = token;
            this.redisLocked = redisLocked;
        }

        public boolean isRedisLocked() {
            return redisLocked;
        }

        @Override
        public void close() {
            owner.release(keys, token);
        }
    }
}
