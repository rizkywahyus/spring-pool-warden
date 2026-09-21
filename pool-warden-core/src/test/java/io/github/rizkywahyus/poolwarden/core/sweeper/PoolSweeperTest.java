package io.github.rizkywahyus.poolwarden.core.sweeper;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PoolSweeperTest {

    private static final String DATA_SOURCE_NAME = "testDataSource";

    private static final long WARN_THRESHOLD_MS = 30_000L;

    private ConnectionTracker tracker;
    private RecordingWardenMetrics metrics;
    private PoolSweeper sweeper;

    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker();
        metrics = new RecordingWardenMetrics();
        WardenConfig config = WardenConfig.builder()
                .warnThresholdMs(WARN_THRESHOLD_MS)
                .sweepIntervalMs(1_000)
                .captureStackTrace(false)
                .build();
        sweeper = new PoolSweeper(tracker, config, metrics);
    }

    @Test
    void leavesYoungConnectionsAlone() {
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);

        sweeper.sweep(nanosAfterCheckout(entry, WARN_THRESHOLD_MS - 1));

        assertThat(metrics.warnedAges()).isEmpty();
        assertThat(entry.isWarned()).isFalse();
    }

    @Test
    void warnsOnceWhenThresholdIsExceeded() {
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);
        long now = nanosAfterCheckout(entry, WARN_THRESHOLD_MS + 5_000);

        sweeper.sweep(now);
        sweeper.sweep(now);

        assertThat(metrics.warnedAges()).containsExactly(35_000L);
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void doesNotWarnAboutConnectionsThatWereClosed() {
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);
        tracker.untrack(entry.id());

        sweeper.sweep(nanosAfterCheckout(entry, WARN_THRESHOLD_MS * 10));

        assertThat(metrics.warnedAges()).isEmpty();
    }

    @Test
    void startIsIdempotentAndCloseStopsTheThread() {
        sweeper.start();
        sweeper.start();

        assertThat(sweeperThreadCount()).isEqualTo(1);

        sweeper.close();
        sweeper.close();
    }

    private long nanosAfterCheckout(TrackedEntry entry, long elapsedMillis) {
        return entry.checkoutNanos() + TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
    }

    private long sweeperThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("pool-warden-sweeper"))
                .count();
    }
}
