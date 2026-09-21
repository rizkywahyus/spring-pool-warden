package io.github.rizkywahyus.poolwarden.core.tracking;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of connections currently checked out of the pool.
 *
 * <p>Entries are keyed by a generated id rather than by the {@link Connection} itself: pool
 * implementations are free to give their proxies surprising {@code equals}/{@code hashCode}
 * semantics, and a connection object may be recycled for a later checkout. The id is handed
 * back to the caller at track time and used to untrack, so lookup never depends on connection
 * identity.
 *
 * <p>Thread-safe: checkout and close happen on application threads while the sweeper reads
 * concurrently.
 */
public final class ConnectionTracker {

    private final Map<Long, TrackedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    /**
     * Records a checkout.
     *
     * @param dataSourceName    which data source handed the connection out
     * @param connection        the physical connection returned by the underlying pool
     * @param captureStackTrace whether to capture the application call-site
     * @return the entry, whose {@link TrackedEntry#id()} must later be passed to {@link #untrack(long)}
     */
    public TrackedEntry track(String dataSourceName, Connection connection, boolean captureStackTrace) {
        long id = idSequence.incrementAndGet();
        List<StackTraceElement> callSite = captureStackTrace ? CallSiteCapture.capture() : List.of();
        TrackedEntry entry = new TrackedEntry(id, dataSourceName, connection, currentThreadName(),
                System.nanoTime(), callSite);
        entries.put(id, entry);
        return entry;
    }

    /**
     * Virtual threads are unnamed unless the application names them, and an empty name makes a
     * leak report useless -- fall back to the thread id so there is always something to grep for.
     */
    private static String currentThreadName() {
        Thread current = Thread.currentThread();
        String name = current.getName();
        return name.isEmpty() ? "thread-" + current.getId() : name;
    }

    /**
     * Removes an entry. Idempotent: calling it twice for the same id (double {@code close()},
     * or a close racing the sweeper) is safe.
     *
     * @return {@code true} if this call actually removed the entry
     */
    public boolean untrack(long id) {
        return entries.remove(id) != null;
    }

    /** A point-in-time view of the tracked connections. The returned list is a copy. */
    public List<TrackedEntry> snapshot() {
        Collection<TrackedEntry> values = entries.values();
        return List.copyOf(values);
    }

    /** Number of connections currently checked out through the warden. */
    public int size() {
        return entries.size();
    }

    /**
     * Number of connections currently checked out of one data source. Counted by scanning
     * rather than kept in a per-name counter: the map holds at most the sum of the pool sizes,
     * and this is only read when metrics are scraped.
     */
    public int size(String dataSourceName) {
        int count = 0;
        for (TrackedEntry entry : entries.values()) {
            if (entry.dataSourceName().equals(dataSourceName)) {
                count++;
            }
        }
        return count;
    }
}
