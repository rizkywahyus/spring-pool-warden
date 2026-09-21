package io.github.rizkywahyus.poolwarden.core.tracking;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConnectionTrackerTest {

    private static final String DATA_SOURCE_NAME = "testDataSource";

    private final ConnectionTracker tracker = new ConnectionTracker();

    @Test
    void assignsDistinctIdsToEachCheckout() {
        Connection connection = mock(Connection.class);

        TrackedEntry first = tracker.track(DATA_SOURCE_NAME, connection, false);
        TrackedEntry second = tracker.track(DATA_SOURCE_NAME, connection, false);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(tracker.size()).isEqualTo(2);
    }

    @Test
    void untrackIsIdempotent() {
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);

        assertThat(tracker.untrack(entry.id())).isTrue();
        assertThat(tracker.untrack(entry.id())).isFalse();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void ageGrowsWithTime() {
        TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);

        long ageMillis = entry.ageMillis(entry.checkoutNanos() + TimeUnit.SECONDS.toNanos(45));

        assertThat(ageMillis).isEqualTo(45_000L);
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);

        var snapshot = tracker.snapshot();
        tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);

        assertThat(snapshot).hasSize(1);
        assertThat(tracker.size()).isEqualTo(2);
    }

    @Test
    void concurrentTrackAndUntrackLeavesNoResidue() throws InterruptedException {
        int threads = 8;
        int checkoutsPerThread = 2_000;
        AtomicInteger removed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < checkoutsPerThread; j++) {
                            TrackedEntry entry = tracker.track(DATA_SOURCE_NAME, mock(Connection.class), false);
                            if (tracker.untrack(entry.id())) {
                                removed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(removed.get()).isEqualTo(threads * checkoutsPerThread);
        assertThat(tracker.size()).isZero();
    }
}
