package io.github.rizkywahyus.poolwarden.core.integration;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.metrics.NoOpWardenMetrics;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.sweeper.PoolSweeper;
import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The test that matters for {@link SweeperMode#REAP}: after leaked connections are reclaimed,
 * the pool must be usable again.
 *
 * <p>Asserting only that {@code abort()} was called is not enough -- a pool tracks its own slots
 * separately from the physical connection, so a connection can be dead while the pool still
 * counts it as in use and refuses to hand out anything else.
 */
class PoolCapacityRecoveryTest {

    private static final long WARN_THRESHOLD_MS = 200L;
    private static final long KILL_THRESHOLD_MS = 400L;
    private static final long SWEEP_INTERVAL_MS = 50L;
    private static final Duration REAP_TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.rizkywahyus.poolwarden.core.integration.TestPools#all")
    void reapFreesPoolCapacity(String poolName, TestPools.PoolFactory factory) throws Exception {
        DataSource pool = factory.create(TestPools.newDatabaseUrl(poolName));
        ConnectionTracker tracker = new ConnectionTracker();
        WardenConfig config = reapConfig();
        WardenDataSource warden = new WardenDataSource(pool, tracker, config);
        PoolSweeper sweeper = new PoolSweeper(tracker, config, NoOpWardenMetrics.INSTANCE);
        sweeper.start();

        List<Connection> leaked = new ArrayList<>();
        try {
            for (int i = 0; i < TestPools.MAX_POOL_SIZE; i++) {
                Connection connection = warden.getConnection();
                query(connection);
                // Deliberately never closed: this is the leak the sweeper has to clean up.
                leaked.add(connection);
            }
            assertThat(tracker.size()).isEqualTo(TestPools.MAX_POOL_SIZE);

            await().atMost(REAP_TIMEOUT).until(() -> tracker.size() == 0);

            // The pool was full of leaked connections; if reaping worked, this now succeeds.
            try (Connection connection = warden.getConnection()) {
                assertThat(query(connection)).isEqualTo(1);
            }
        } finally {
            closeQuietly(leaked);
            sweeper.close();
            TestPools.close(pool);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.rizkywahyus.poolwarden.core.integration.TestPools#all")
    void closedConnectionsAreNeverReaped(String poolName, TestPools.PoolFactory factory) throws Exception {
        DataSource pool = factory.create(TestPools.newDatabaseUrl(poolName));
        ConnectionTracker tracker = new ConnectionTracker();
        WardenConfig config = reapConfig();
        WardenDataSource warden = new WardenDataSource(pool, tracker, config);
        PoolSweeper sweeper = new PoolSweeper(tracker, config, NoOpWardenMetrics.INSTANCE);
        sweeper.start();

        try {
            // Well-behaved usage, repeated for longer than the kill threshold: nothing here is
            // ever held long enough to be reaped, and the pool keeps serving.
            long deadline = System.nanoTime() + Duration.ofMillis(KILL_THRESHOLD_MS * 3).toNanos();
            while (System.nanoTime() < deadline) {
                try (Connection connection = warden.getConnection()) {
                    assertThat(query(connection)).isEqualTo(1);
                }
            }
            assertThat(tracker.size()).isZero();
        } finally {
            sweeper.close();
            TestPools.close(pool);
        }
    }

    private static WardenConfig reapConfig() {
        return WardenConfig.builder()
                .mode(SweeperMode.REAP)
                .warnThresholdMs(WARN_THRESHOLD_MS)
                .killThresholdMs(KILL_THRESHOLD_MS)
                .sweepIntervalMs(SWEEP_INTERVAL_MS)
                .captureStackTrace(false)
                .build();
    }

    private static int query(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void closeQuietly(List<Connection> connections) {
        for (Connection connection : connections) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already reaped; the application closing afterwards must not fail the test.
            }
        }
    }
}
