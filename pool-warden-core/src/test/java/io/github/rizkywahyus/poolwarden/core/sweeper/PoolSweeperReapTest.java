package io.github.rizkywahyus.poolwarden.core.sweeper;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PoolSweeperReapTest {

    private static final String DATA_SOURCE_NAME = "testDataSource";

    private static final long WARN_THRESHOLD_MS = 1_000L;
    private static final long KILL_THRESHOLD_MS = 5_000L;

    private ConnectionTracker tracker;
    private RecordingWardenMetrics metrics;
    private ConnectionReaper reaper;
    private PoolSweeper sweeper;

    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker();
        metrics = new RecordingWardenMetrics();
        reaper = new ConnectionReaper();
        WardenConfig config = WardenConfig.builder()
                .mode(SweeperMode.REAP)
                .warnThresholdMs(WARN_THRESHOLD_MS)
                .killThresholdMs(KILL_THRESHOLD_MS)
                .sweepIntervalMs(1_000)
                .captureStackTrace(false)
                .build();
        sweeper = new PoolSweeper(tracker, config, metrics, reaper);
    }

    @AfterEach
    void tearDown() {
        sweeper.close();
    }

    @Test
    void warnsButDoesNotReapBeforeTheKillThreshold() throws SQLException {
        Connection connection = mock(Connection.class);
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, connection, false);

        sweeper.sweep(nanosAfterCheckout(entry, KILL_THRESHOLD_MS - 1));

        assertThat(metrics.warnedAges()).hasSize(1);
        assertThat(metrics.reapedAges()).isEmpty();
        verify(connection, never()).abort(any());
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void abortsOnceOnceTheKillThresholdIsPassed() throws SQLException {
        Connection connection = mock(Connection.class);
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, connection, false);
        long now = nanosAfterCheckout(entry, KILL_THRESHOLD_MS);

        sweeper.sweep(now);
        sweeper.sweep(now);

        verify(connection, times(1)).abort(any(Executor.class));
        assertThat(metrics.reapedAges()).containsExactly(KILL_THRESHOLD_MS);
        assertThat(tracker.size()).isZero();
        assertThat(entry.state()).isEqualTo(TrackedEntry.State.REAPED);
    }

    @Test
    void fallsBackToCloseWhenTheDriverHasNoAbort() throws SQLException {
        Connection connection = mock(Connection.class);
        doThrow(new SQLFeatureNotSupportedException("abort not supported"))
                .when(connection).abort(any());
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, connection, false);

        sweeper.sweep(nanosAfterCheckout(entry, KILL_THRESHOLD_MS));

        verify(connection).close();
        assertThat(metrics.reapedAges()).hasSize(1);
    }

    @Test
    void returnsTheSlotToThePoolAfterAborting() throws SQLException {
        Connection connection = mock(Connection.class);
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, connection, false);

        sweeper.sweep(nanosAfterCheckout(entry, KILL_THRESHOLD_MS));

        // Aborting kills the connection; only close() makes the pool count the slot as free.
        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    void doesNotReportAReapWhenTheDriverFailsEntirely() throws SQLException {
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("connection already gone")).when(connection).abort(any());
        doThrow(new SQLException("pool refused the connection back")).when(connection).close();
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, connection, false);

        sweeper.sweep(nanosAfterCheckout(entry, KILL_THRESHOLD_MS));

        assertThat(metrics.reapedAges()).isEmpty();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void closeRacingTheSweeperReleasesTheEntryExactlyOnce() throws Exception {
        int rounds = 2_000;
        DataSource pool = mock(DataSource.class);
        WardenConfig config = WardenConfig.builder()
                .mode(SweeperMode.REAP)
                .warnThresholdMs(1)
                .killThresholdMs(2)
                .sweepIntervalMs(1_000)
                .captureStackTrace(false)
                .build();
        WardenDataSource dataSource = new WardenDataSource(pool, tracker, config);
        PoolSweeper racingSweeper = new PoolSweeper(tracker, config, metrics, reaper);
        int reapedBefore = metrics.reapedAges().size();
        int reapedRounds = 0;

        ExecutorService closers = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < rounds; i++) {
                Connection physical = mock(Connection.class);
                when(pool.getConnection()).thenReturn(physical);
                Connection handed = dataSource.getConnection();
                TrackedEntry entry = tracker.snapshot().get(0);

                CountDownLatch bothReady = new CountDownLatch(2);
                Future<?> closing = closers.submit(() -> {
                    bothReady.countDown();
                    bothReady.await();
                    handed.close();
                    return null;
                });
                bothReady.countDown();
                bothReady.await();
                // Every tracked connection is already past this config's kill threshold.
                racingSweeper.sweep(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
                closing.get(10, TimeUnit.SECONDS);

                // The application always closes, and abort happens only when the sweeper won
                // the entry -- never both for the same side, never twice.
                verify(physical, times(1)).close();
                verify(physical, atMost(1)).abort(any());
                assertThat(entry.state()).isIn(TrackedEntry.State.CLOSED, TrackedEntry.State.REAPED);
                if (entry.state() == TrackedEntry.State.REAPED) {
                    verify(physical, times(1)).abort(any());
                    reapedRounds++;
                } else {
                    verify(physical, never()).abort(any());
                }
                assertThat(tracker.size()).isZero();
            }
        } finally {
            closers.shutdownNow();
            racingSweeper.close();
        }

        // One metric event per reaped round, so no entry was reaped twice.
        assertThat(metrics.reapedAges()).hasSize(reapedBefore + reapedRounds);
    }

    private long nanosAfterCheckout(TrackedEntry entry, long elapsedMillis) {
        return entry.checkoutNanos() + TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
    }
}
