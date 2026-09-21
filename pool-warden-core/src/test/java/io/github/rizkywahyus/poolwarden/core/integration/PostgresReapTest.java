package io.github.rizkywahyus.poolwarden.core.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.metrics.NoOpWardenMetrics;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.sweeper.PoolSweeper;
import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves against a real PostgreSQL what the mock-based tests cannot: that reaping ends the
 * session on the server, not just on the client.
 *
 * <p>Skipped automatically when Docker is not available, so it runs in CI without blocking a
 * local build.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresReapTest {

    private static final int MAX_POOL_SIZE = 2;
    private static final long WARN_THRESHOLD_MS = 500L;
    private static final long KILL_THRESHOLD_MS = 1_000L;
    private static final long SWEEP_INTERVAL_MS = 100L;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void reapEndsTheBackendAndGivesThePoolItsCapacityBack() throws Exception {
        ConnectionTracker tracker = new ConnectionTracker();
        WardenConfig config = WardenConfig.builder()
                .mode(SweeperMode.REAP)
                .warnThresholdMs(WARN_THRESHOLD_MS)
                .killThresholdMs(KILL_THRESHOLD_MS)
                .sweepIntervalMs(SWEEP_INTERVAL_MS)
                .captureStackTrace(false)
                .build();

        HikariDataSource pool = pool();
        WardenDataSource warden = new WardenDataSource(pool, "postgres", tracker, config);
        PoolSweeper sweeper = new PoolSweeper(tracker, config, NoOpWardenMetrics.INSTANCE);
        sweeper.start();

        List<Connection> leaked = new ArrayList<>();
        List<Integer> backendPids = new ArrayList<>();
        try {
            for (int i = 0; i < MAX_POOL_SIZE; i++) {
                Connection connection = warden.getConnection();
                backendPids.add(backendPid(connection));
                // Never closed: the leak the sweeper has to clean up.
                leaked.add(connection);
            }
            assertThat(backendPids).doesNotHaveDuplicates();
            assertThat(liveBackends(backendPids)).isEqualTo(MAX_POOL_SIZE);

            await().atMost(TIMEOUT).until(() -> tracker.size() == 0);

            // The database no longer has those sessions ...
            await().atMost(TIMEOUT).until(() -> liveBackends(backendPids) == 0);

            // ... and the pool hands out connections again.
            try (Connection connection = warden.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        } finally {
            for (Connection connection : leaked) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Already reaped.
                }
            }
            sweeper.close();
            pool.close();
        }
    }

    private static HikariDataSource pool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** Counts how many of the given backends PostgreSQL still reports as connected. */
    private static int liveBackends(List<Integer> pids) throws SQLException {
        // Deliberately outside the pool: the pool is the thing under test.
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE pid = ANY (?)")) {
            statement.setArray(1, admin.createArrayOf("int", pids.toArray()));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
