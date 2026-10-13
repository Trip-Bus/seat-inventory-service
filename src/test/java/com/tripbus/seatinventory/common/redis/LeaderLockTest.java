package com.tripbus.seatinventory.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderLockTest {

    private static final String JOB = "seat-inventory-expired-holds";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement tryLock = mock(PreparedStatement.class);
    private final PreparedStatement unlock = mock(PreparedStatement.class);
    private final ResultSet tryLockResult = mock(ResultSet.class);
    private final ResultSet unlockResult = mock(ResultSet.class);
    private final AtomicInteger ejecuciones = new AtomicInteger();
    private final LeaderLock leaderLock = new LeaderLock(redis, dataSource);

    @BeforeEach
    void setUp() throws SQLException {
        when(redis.opsForValue()).thenReturn(values);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(tryLock);
        lenient().when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        lenient().when(tryLock.executeQuery()).thenReturn(tryLockResult);
        lenient().when(unlock.executeQuery()).thenReturn(unlockResult);
        lenient().when(unlockResult.next()).thenReturn(true);
        lenient().when(unlockResult.getBoolean(1)).thenReturn(true);
    }

    @Test
    void conRedisSoloCorreQuienTomaLaLlave() throws SQLException {
        when(values.setIfAbsent(eq("job-lock:" + JOB), anyString(), any(Duration.class))).thenReturn(true, false);

        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), ejecuciones::incrementAndGet)).isTrue();
        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), ejecuciones::incrementAndGet)).isFalse();
        assertThat(ejecuciones).hasValue(1);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void unErrorDelJobNoSePropaga() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), () -> {
            throw new IllegalStateException("falla");
        })).isTrue();
    }

    @Test
    void sinRedisEligeLiderConElAdvisoryLockDePostgresYLoLibera() throws SQLException {
        redisCaido();
        when(tryLockResult.next()).thenReturn(true);
        when(tryLockResult.getBoolean(1)).thenReturn(true);

        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), ejecuciones::incrementAndGet)).isTrue();

        assertThat(ejecuciones).hasValue(1);
        verify(tryLock).setLong(1, LeaderLock.advisoryKey(JOB));
        verify(unlock).setLong(1, LeaderLock.advisoryKey(JOB));
        verify(connection).close();
    }

    @Test
    void sinRedisYConOtroNodoComoLiderNoCorre() throws SQLException {
        redisCaido();
        when(tryLockResult.next()).thenReturn(true);
        when(tryLockResult.getBoolean(1)).thenReturn(false);

        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), ejecuciones::incrementAndGet)).isFalse();

        assertThat(ejecuciones).hasValue(0);
        verify(unlock, never()).executeQuery();
    }

    @Test
    void sinRedisNiPostgresNoCorre() throws SQLException {
        redisCaido();
        when(dataSource.getConnection()).thenThrow(new SQLException("sin conexión"));

        assertThat(leaderLock.runIfLeader(JOB, Duration.ofSeconds(4), ejecuciones::incrementAndGet)).isFalse();
        assertThat(ejecuciones).hasValue(0);
    }

    @Test
    void laLlaveDelAdvisoryLockEsEstableYDistintaPorJob() {
        assertThat(LeaderLock.advisoryKey(JOB)).isEqualTo(LeaderLock.advisoryKey(JOB));
        assertThat(LeaderLock.advisoryKey(JOB)).isNotEqualTo(LeaderLock.advisoryKey("otro-job"));
    }

    private void redisCaido() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis caído"));
    }
}
