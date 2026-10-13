package com.tripbus.seatinventory.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * "Elección de líder" mínima para jobs programados: solo la réplica que obtiene la llave
 * (SET NX PX) ejecuta el job en esa ventana. La llave expira sola (no se libera), así que
 * en cada intervalo corre en un único nodo.
 * <p>
 * Respaldo sin Redis: si Redis no responde, el líder se elige con {@code pg_try_advisory_lock}
 * de PostgreSQL (lock de sesión en una conexión propia, liberado al terminar el job), para que
 * los jobs críticos (expirar retenciones) sigan corriendo en un único nodo a la vez.
 */
@Component
public class LeaderLock {

    private static final Logger log = LoggerFactory.getLogger(LeaderLock.class);
    /** Primera mitad de la llave del advisory lock: separa estos locks de otros usos en la BD. */
    private static final long NAMESPACE = 0x5452_4950L; // "TRIP"

    private final StringRedisTemplate redis;
    private final DataSource dataSource;
    private final String instanceId = UUID.randomUUID().toString();

    public LeaderLock(StringRedisTemplate redis, DataSource dataSource) {
        this.redis = redis;
        this.dataSource = dataSource;
    }

    public boolean runIfLeader(String jobName, Duration window, Runnable job) {
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent("job-lock:" + jobName, instanceId, window);
        } catch (RuntimeException e) {
            log.warn("Redis no disponible para el lock del job {} ({}); se usa el advisory lock de PostgreSQL",
                    jobName, e.getMessage());
            return runIfPostgresLeader(jobName, job);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        run(jobName, job);
        return true;
    }

    boolean runIfPostgresLeader(String jobName, Runnable job) {
        long key = advisoryKey(jobName);
        try (Connection connection = dataSource.getConnection()) {
            if (!advisory(connection, "SELECT pg_try_advisory_lock(?)", key)) {
                return false;
            }
            try {
                run(jobName, job);
            } finally {
                advisory(connection, "SELECT pg_advisory_unlock(?)", key);
            }
            return true;
        } catch (SQLException e) {
            log.warn("Tampoco se pudo tomar el advisory lock del job {}: {}", jobName, e.getMessage());
            return false;
        }
    }

    static long advisoryKey(String jobName) {
        CRC32 crc = new CRC32();
        crc.update(jobName.getBytes(StandardCharsets.UTF_8));
        return (NAMESPACE << 32) | crc.getValue();
    }

    private static boolean advisory(Connection connection, String sql, long key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void run(String jobName, Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            log.error("Error ejecutando el job {}", jobName, e);
        }
    }
}
