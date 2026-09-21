package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Publishes warden events to Micrometer, so leaks show up on the dashboards a team already has
 * instead of only in the logs.
 *
 * <p>Meters, every one tagged {@code datasource} with the bean name of the pool it came from so
 * an application with several pools can tell them apart:
 * <ul>
 *   <li>{@code pool.warden.connections.active} -- connections currently checked out</li>
 *   <li>{@code pool.warden.leaks.warned} -- leaks reported, one increment per leak</li>
 *   <li>{@code pool.warden.leaks.reaped} -- leaks force-reclaimed</li>
 *   <li>{@code pool.warden.leaks.age} -- how long a leaked connection had been held, tagged
 *       {@code event} with {@code warned} or {@code reaped}</li>
 * </ul>
 *
 * <p>All of them are registered as soon as a data source is known, so a dashboard or alert can
 * be built against a pool that has not leaked yet instead of waiting for the first incident to
 * make the meter appear. Micrometer returns the same instrument for the same name and tags, so
 * the later event-time lookups reuse those registrations; leaks are rare by definition, so that
 * lookup is not on any hot path.
 */
public class MicrometerWardenMetrics implements WardenMetrics {

    private static final String DATASOURCE_TAG = "datasource";
    private static final String EVENT_TAG = "event";
    private static final String WARNED_EVENT = "warned";
    private static final String REAPED_EVENT = "reaped";

    private final MeterRegistry registry;

    public MicrometerWardenMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void dataSourceRegistered(String dataSourceName, IntSupplier activeConnections) {
        warnedCounter(dataSourceName);
        reapedCounter(dataSourceName);
        ageTimer(dataSourceName, WARNED_EVENT);
        ageTimer(dataSourceName, REAPED_EVENT);
        Gauge.builder("pool.warden.connections.active", activeConnections, IntSupplier::getAsInt)
                .description("Connections currently checked out through pool-warden")
                .tag(DATASOURCE_TAG, dataSourceName)
                // Micrometer holds gauge subjects weakly by default; nothing else keeps this
                // supplier alive, so without a strong reference the meter disappears at the
                // first garbage collection.
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void leakWarned(String dataSourceName, long ageMillis) {
        warnedCounter(dataSourceName).increment();
        ageTimer(dataSourceName, WARNED_EVENT).record(ageMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void connectionReaped(String dataSourceName, long ageMillis) {
        reapedCounter(dataSourceName).increment();
        ageTimer(dataSourceName, REAPED_EVENT).record(ageMillis, TimeUnit.MILLISECONDS);
    }

    private Counter warnedCounter(String dataSourceName) {
        return Counter.builder("pool.warden.leaks.warned")
                .description("Connections held past the warn threshold")
                .tag(DATASOURCE_TAG, dataSourceName)
                .register(registry);
    }

    private Counter reapedCounter(String dataSourceName) {
        return Counter.builder("pool.warden.leaks.reaped")
                .description("Leaked connections force-reclaimed by pool-warden")
                .tag(DATASOURCE_TAG, dataSourceName)
                .register(registry);
    }

    private Timer ageTimer(String dataSourceName, String event) {
        return Timer.builder("pool.warden.leaks.age")
                .description("How long leaked connections had been held when acted on")
                .tag(DATASOURCE_TAG, dataSourceName)
                .tag(EVENT_TAG, event)
                .register(registry);
    }
}
