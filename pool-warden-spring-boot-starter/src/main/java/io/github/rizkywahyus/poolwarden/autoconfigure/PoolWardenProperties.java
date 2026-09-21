package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the warden, bound from {@code pool-warden.*}.
 *
 * <p>Durations accept the usual Spring Boot notation ({@code 30s}, {@code 2m}).
 */
@ConfigurationProperties("pool-warden")
public class PoolWardenProperties {

    /** Whether to wrap the application's data sources at all. */
    private boolean enabled = true;

    /** WARN_ONLY logs and counts leaks; REAP also force-closes them past the kill threshold. */
    private SweeperMode mode = SweeperMode.WARN_ONLY;

    /** How long a connection may stay checked out before it is reported as a leak. */
    private Duration warnThreshold = Duration.ofSeconds(30);

    /** How long before a leaked connection is force-reclaimed. Only used when mode is REAP. */
    private Duration killThreshold = Duration.ofSeconds(120);

    /** How often tracked connections are inspected. */
    private Duration sweepInterval = Duration.ofSeconds(10);

    /**
     * Whether to record the call-site that checked out each connection. Makes leak reports
     * actionable, but costs a stack walk per checkout -- consider turning it off on hot paths.
     */
    private boolean captureStackTrace = true;

    public WardenConfig toWardenConfig() {
        return WardenConfig.builder()
                .mode(mode)
                .warnThresholdMs(warnThreshold.toMillis())
                .killThresholdMs(killThreshold.toMillis())
                .sweepIntervalMs(sweepInterval.toMillis())
                .captureStackTrace(captureStackTrace)
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SweeperMode getMode() {
        return mode;
    }

    public void setMode(SweeperMode mode) {
        this.mode = mode;
    }

    public Duration getWarnThreshold() {
        return warnThreshold;
    }

    public void setWarnThreshold(Duration warnThreshold) {
        this.warnThreshold = warnThreshold;
    }

    public Duration getKillThreshold() {
        return killThreshold;
    }

    public void setKillThreshold(Duration killThreshold) {
        this.killThreshold = killThreshold;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public boolean isCaptureStackTrace() {
        return captureStackTrace;
    }

    public void setCaptureStackTrace(boolean captureStackTrace) {
        this.captureStackTrace = captureStackTrace;
    }
}
