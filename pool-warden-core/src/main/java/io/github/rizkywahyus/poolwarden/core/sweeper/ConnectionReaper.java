package io.github.rizkywahyus.poolwarden.core.sweeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Force-releases a leaked connection in two steps: abort the physical connection, then hand the
 * pool slot back.
 *
 * <p>{@link Connection#abort(java.util.concurrent.Executor)} is the JDBC 4.1 standard way to tear
 * a connection down from another thread; using it keeps the warden pool- and driver-agnostic
 * instead of needing an adapter per vendor. Drivers that never implemented it fall back to
 * {@code close()} alone, which is weaker (it can block on the very statement that is stuck) but
 * better than leaving the connection checked out.
 *
 * <p>Aborting on its own is not enough. A pool counts a slot as in use until {@code close()} is
 * called on the connection <em>it</em> handed out, and on a real leak the application never makes
 * that call -- so the connection would be dead while the pool stayed exhausted. Closing after the
 * abort is what actually gives the capacity back. Pools notice that the underlying connection is
 * gone and discard it instead of pooling it again, which is why {@code close()} here is best-effort
 * and its failures are expected rather than exceptional.
 *
 * <p>The abort itself runs on this reaper's own small pool: the driver is given that executor to
 * do its cleanup. Some drivers (pgjdbc among them) only schedule the teardown there and return, so
 * the reaper waits for it to finish before closing. Closing first would hand the pool a connection
 * that still looks healthy; HikariCP skips its liveness check for recently used connections and
 * would lend it out again moments before it dies. The wait is bounded, so one hung driver delays a
 * sweep by at most {@value #ABORT_TIMEOUT_SECONDS} s instead of stalling it.
 */
public final class ConnectionReaper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionReaper.class);
    private static final String THREAD_NAME_PREFIX = "pool-warden-reaper-";
    private static final int MAX_THREADS = 2;
    private static final int QUEUE_CAPACITY = 64;
    private static final long IDLE_TIMEOUT_SECONDS = 30L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final long ABORT_TIMEOUT_SECONDS = 5L;

    private final ExecutorService abortExecutor;
    private final AtomicBoolean fallbackLogged = new AtomicBoolean(false);

    public ConnectionReaper() {
        this.abortExecutor = newAbortExecutor();
    }

    private static ExecutorService newAbortExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, MAX_THREADS,
                IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                // A full queue means aborts are piling up; run on the sweeper thread rather than
                // dropping the reclaim silently.
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Kills the connection and returns its pool slot.
     *
     * @return {@code true} if the connection was released by either step, {@code false} if both
     *         the abort and the close failed and the leak is still holding the slot
     */
    public boolean reap(Connection connection) {
        boolean aborted = abort(connection);
        boolean returned = returnToPool(connection);
        return aborted || returned;
    }

    /** @return {@code true} if the physical connection was torn down. */
    private boolean abort(Connection connection) {
        List<Future<?>> teardown = new CopyOnWriteArrayList<>();
        try {
            connection.abort(command -> teardown.add(abortExecutor.submit(command)));
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
            if (fallbackLogged.compareAndSet(false, true)) {
                log.warn("JDBC driver {} does not support Connection.abort(); "
                                + "pool-warden will only close leaked connections instead",
                        connection.getClass().getName(), e);
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to abort leaked connection", e);
            return false;
        }
        return awaitTeardown(teardown);
    }

    /**
     * Waits for the work the driver scheduled during {@code abort()}. Drivers that tear down
     * synchronously schedule nothing, and this returns at once.
     */
    private boolean awaitTeardown(List<Future<?>> teardown) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ABORT_TIMEOUT_SECONDS);
        try {
            for (Future<?> task : teardown) {
                task.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            log.warn("JDBC driver did not finish aborting a leaked connection within {} s; "
                    + "returning it to the pool anyway", ABORT_TIMEOUT_SECONDS);
            return false;
        } catch (ExecutionException e) {
            log.warn("JDBC driver failed while aborting a leaked connection", e.getCause());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Gives the slot back by closing the connection the pool handed out. After an abort the pool
     * usually reports that the connection is already gone; that is the expected outcome, not a
     * failure, so it is logged at debug level only.
     *
     * @return {@code true} if the close completed without the pool reporting a problem
     */
    private boolean returnToPool(Connection connection) {
        try {
            connection.close();
            return true;
        } catch (Exception e) {
            log.debug("Pool reported an error while taking back an aborted connection; "
                    + "the slot is normally released anyway", e);
            return false;
        }
    }

    @Override
    public void close() {
        abortExecutor.shutdown();
        try {
            if (!abortExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                abortExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            abortExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
