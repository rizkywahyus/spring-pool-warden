package io.github.rizkywahyus.poolwarden.core.tracking;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One connection currently checked out of the pool.
 *
 * <p>Created when the application calls {@code getConnection()} and removed when it calls
 * {@code close()}. An entry that outlives the configured threshold is what the warden
 * reports as a leak.
 */
public final class TrackedEntry {

    private final long id;
    private final String dataSourceName;
    private final Connection connection;
    private final String threadName;
    private final long checkoutNanos;
    private final List<StackTraceElement> callSite;
    private final AtomicBoolean warned = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    TrackedEntry(long id, String dataSourceName, Connection connection, String threadName,
                 long checkoutNanos, List<StackTraceElement> callSite) {
        this.id = id;
        this.dataSourceName = Objects.requireNonNull(dataSourceName, "dataSourceName must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.threadName = Objects.requireNonNull(threadName, "threadName must not be null");
        this.checkoutNanos = checkoutNanos;
        this.callSite = callSite == null ? List.of() : List.copyOf(callSite);
    }

    public long id() {
        return id;
    }

    /** Which data source handed this connection out, so leaks can be attributed per pool. */
    public String dataSourceName() {
        return dataSourceName;
    }

    /** The physical connection handed out by the underlying pool, not the warden proxy. */
    public Connection connection() {
        return connection;
    }

    public String threadName() {
        return threadName;
    }

    public long checkoutNanos() {
        return checkoutNanos;
    }

    public List<StackTraceElement> callSite() {
        return callSite;
    }

    /** How long this connection has been checked out, in milliseconds. */
    public long ageMillis(long nowNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - checkoutNanos);
    }

    /**
     * What has happened to this checkout. The transition out of {@link State#ACTIVE} is a
     * compare-and-set, which is how a close racing the sweeper is resolved: exactly one side
     * wins and the loser does nothing.
     */
    public enum State {
        /** Checked out and in use as far as the warden can tell. */
        ACTIVE,
        /** The application closed it. */
        CLOSED,
        /** The sweeper force-reclaimed it. */
        REAPED
    }

    /**
     * Claims this entry on behalf of the application closing the connection.
     *
     * @return {@code true} if this call won the race and should untrack the entry
     */
    public boolean tryClose() {
        return state.compareAndSet(State.ACTIVE, State.CLOSED);
    }

    /**
     * Claims this entry on behalf of the sweeper.
     *
     * @return {@code true} if this call won the race and should abort the connection
     */
    public boolean tryReap() {
        return state.compareAndSet(State.ACTIVE, State.REAPED);
    }

    public State state() {
        return state.get();
    }

    /**
     * Marks this entry as reported so a single leak is not logged again on every sweep.
     *
     * @return {@code true} for the first caller only
     */
    public boolean markWarned() {
        return warned.compareAndSet(false, true);
    }

    public boolean isWarned() {
        return warned.get();
    }

    @Override
    public String toString() {
        return "TrackedEntry[id=" + id + ", dataSource=" + dataSourceName + ", thread=" + threadName + "]";
    }
}
