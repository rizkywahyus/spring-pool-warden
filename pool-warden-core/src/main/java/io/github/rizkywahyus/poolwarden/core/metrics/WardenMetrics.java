package io.github.rizkywahyus.poolwarden.core.metrics;

import java.util.function.IntSupplier;

/**
 * Sink for warden events.
 *
 * <p>An interface rather than a direct Micrometer dependency so the core module stays plain
 * Java: the Spring Boot starter supplies a Micrometer-backed implementation, and anyone using
 * the core on its own can plug in their own or keep {@link NoOpWardenMetrics}.
 */
public interface WardenMetrics {

    /**
     * A data source is now being watched. Implementations that publish a live count of checked-out
     * connections register it here; the supplier reads that count on demand.
     *
     * <p>Defaulted to a no-op so implementations that only care about leak events stay small.
     */
    default void dataSourceRegistered(String dataSourceName, IntSupplier activeConnections) {
        // nothing to register
    }

    /** A connection has been checked out longer than the warn threshold. Fired once per leak. */
    void leakWarned(String dataSourceName, long ageMillis);

    /** A leaked connection has been force-reclaimed. */
    void connectionReaped(String dataSourceName, long ageMillis);
}
