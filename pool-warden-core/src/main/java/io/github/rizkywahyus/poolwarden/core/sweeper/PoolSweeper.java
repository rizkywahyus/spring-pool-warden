package io.github.rizkywahyus.poolwarden.core.sweeper;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.github.rizkywahyus.poolwarden.core.tracking.CallSiteCapture;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically inspects the {@link ConnectionTracker} and reports connections that have been
 * checked out for too long.
 *
 * <p>Runs on a single daemon thread so it never keeps a JVM alive, and each sweep swallows
 * unexpected errors: a scheduled task that throws is silently cancelled by the executor, which
 * would leave the application with monitoring that looks enabled but does nothing.
 */
public final class PoolSweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PoolSweeper.class);
    private static final String THREAD_NAME = "pool-warden-sweeper";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final ConnectionTracker tracker;
    private final WardenConfig config;
    private final WardenMetrics metrics;
    private final ConnectionReaper reaper;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ScheduledExecutorService scheduler;

    public PoolSweeper(ConnectionTracker tracker, WardenConfig config, WardenMetrics metrics) {
        this(tracker, config, metrics,
                config.mode() == SweeperMode.REAP ? new ConnectionReaper() : null);
    }

    /** Lets tests supply their own reaper. */
    PoolSweeper(ConnectionTracker tracker, WardenConfig config, WardenMetrics metrics, ConnectionReaper reaper) {
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.reaper = reaper;
    }

    /** Starts the scheduled sweeps. Calling it more than once has no effect. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        scheduler.scheduleWithFixedDelay(this::sweepQuietly,
                config.sweepIntervalMs(), config.sweepIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("pool-warden started: {}", config);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException | Error e) {
            log.error("pool-warden sweep failed; monitoring continues on the next interval", e);
        }
    }

    private void sweep() {
        sweep(System.nanoTime());
    }

    /**
     * Runs a single sweep against a given clock reading. Package-visible so tests can age
     * connections without sleeping.
     */
    void sweep(long nowNanos) {
        for (TrackedEntry entry : tracker.snapshot()) {
            long ageMillis = entry.ageMillis(nowNanos);
            if (ageMillis < config.warnThresholdMs()) {
                continue;
            }
            if (entry.markWarned()) {
                reportLeak(entry, ageMillis);
            }
            if (reaper != null && ageMillis >= config.killThresholdMs()) {
                reap(entry, ageMillis);
            }
        }
    }

    /**
     * Force-releases a leaked connection. {@link TrackedEntry#tryClose()} and
     * {@link TrackedEntry#tryReap()} compete for the same entry, so a connection the
     * application closed a moment ago is never aborted.
     */
    private void reap(TrackedEntry entry, long ageMillis) {
        if (!entry.tryReap()) {
            return;
        }
        tracker.untrack(entry.id());
        if (reaper.reap(entry.connection())) {
            log.warn("Reclaimed connection from '{}' held for {} ms (kill threshold {} ms) by thread '{}'. "
                            + "Any transaction it was running has been aborted.",
                    entry.dataSourceName(), ageMillis, config.killThresholdMs(), entry.threadName());
            metrics.connectionReaped(entry.dataSourceName(), ageMillis);
        }
    }

    private void reportLeak(TrackedEntry entry, long ageMillis) {
        log.warn("Connection from '{}' held for {} ms (threshold {} ms) by thread '{}'. "
                        + "Not closed yet -- likely a leak. Checked out at:{}",
                entry.dataSourceName(), ageMillis, config.warnThresholdMs(), entry.threadName(),
                CallSiteCapture.format(entry.callSite()));
        metrics.leakWarned(entry.dataSourceName(), ageMillis);
    }

    /**
     * Stops sweeping, then shuts the reaper down -- in that order. A sweep still running would
     * otherwise hand aborts to an executor that is already shutting down, where they are
     * rejected and, under the caller-runs policy, silently discarded.
     */
    @Override
    public void close() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdownNow();
            try {
                if (!current.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("pool-warden sweeper did not stop within {} s", SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            started.set(false);
        }
        if (reaper != null) {
            reaper.close();
        }
    }
}
