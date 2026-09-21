package io.github.rizkywahyus.poolwarden.autoconfigure.actuate;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the connections currently checked out at {@code /actuator/poolwarden}, oldest first,
 * so a pool that is filling up can be inspected while it happens instead of afterwards in logs.
 *
 * <p>Read-only on purpose: reaping is driven by configuration, not by an HTTP call.
 */
@Endpoint(id = "poolwarden")
public class PoolWardenEndpoint {

    private static final int MAX_CALL_SITE_FRAMES = 5;

    private final ConnectionTracker tracker;
    private final WardenConfig config;

    public PoolWardenEndpoint(ConnectionTracker tracker, WardenConfig config) {
        this.tracker = tracker;
        this.config = config;
    }

    @ReadOperation
    public Map<String, Object> activeConnections() {
        long nowNanos = System.nanoTime();
        List<Map<String, Object>> connections = tracker.snapshot().stream()
                .sorted(Comparator.comparingLong((TrackedEntry entry) -> entry.ageMillis(nowNanos)).reversed())
                .map(entry -> describe(entry, nowNanos))
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", config.mode());
        report.put("warnThresholdMs", config.warnThresholdMs());
        report.put("killThresholdMs", config.killThresholdMs());
        report.put("activeConnections", connections.size());
        report.put("connections", connections);
        return report;
    }

    private Map<String, Object> describe(TrackedEntry entry, long nowNanos) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", entry.id());
        details.put("dataSource", entry.dataSourceName());
        details.put("thread", entry.threadName());
        details.put("ageMs", entry.ageMillis(nowNanos));
        details.put("warned", entry.isWarned());
        details.put("callSite", entry.callSite().stream()
                .limit(MAX_CALL_SITE_FRAMES)
                .map(StackTraceElement::toString)
                .toList());
        return details;
    }
}
