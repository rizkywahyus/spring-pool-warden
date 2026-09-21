package io.github.rizkywahyus.poolwarden.core.config;

import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;

import java.util.Objects;

/**
 * Immutable configuration for the warden. Built through {@link #builder()}; all
 * values are validated eagerly so a misconfigured application fails at startup
 * rather than at the first sweep.
 */
public final class WardenConfig {

    private static final long DEFAULT_WARN_THRESHOLD_MS = 30_000L;
    private static final long DEFAULT_KILL_THRESHOLD_MS = 120_000L;
    private static final long DEFAULT_SWEEP_INTERVAL_MS = 10_000L;

    private final long warnThresholdMs;
    private final long killThresholdMs;
    private final long sweepIntervalMs;
    private final SweeperMode mode;
    private final boolean captureStackTrace;

    private WardenConfig(Builder builder) {
        this.warnThresholdMs = builder.warnThresholdMs;
        this.killThresholdMs = builder.killThresholdMs;
        this.sweepIntervalMs = builder.sweepIntervalMs;
        this.mode = Objects.requireNonNull(builder.mode, "mode must not be null");
        this.captureStackTrace = builder.captureStackTrace;
        validate();
    }

    private void validate() {
        requirePositive(warnThresholdMs, "warnThresholdMs");
        requirePositive(sweepIntervalMs, "sweepIntervalMs");
        if (mode == SweeperMode.REAP) {
            requirePositive(killThresholdMs, "killThresholdMs");
            if (killThresholdMs <= warnThresholdMs) {
                throw new IllegalArgumentException(
                        "killThresholdMs (" + killThresholdMs + ") must be greater than warnThresholdMs ("
                                + warnThresholdMs + ") so a leak is always warned about before being reaped");
            }
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than 0 but was " + value);
        }
    }

    public long warnThresholdMs() {
        return warnThresholdMs;
    }

    public long killThresholdMs() {
        return killThresholdMs;
    }

    public long sweepIntervalMs() {
        return sweepIntervalMs;
    }

    public SweeperMode mode() {
        return mode;
    }

    public boolean captureStackTrace() {
        return captureStackTrace;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Defaults: warn after 30s, sweep every 10s, {@link SweeperMode#WARN_ONLY}, stack traces on. */
    public static WardenConfig defaults() {
        return builder().build();
    }

    @Override
    public String toString() {
        return "WardenConfig[mode=" + mode
                + ", warnThresholdMs=" + warnThresholdMs
                + ", killThresholdMs=" + killThresholdMs
                + ", sweepIntervalMs=" + sweepIntervalMs
                + ", captureStackTrace=" + captureStackTrace + "]";
    }

    public static final class Builder {

        private long warnThresholdMs = DEFAULT_WARN_THRESHOLD_MS;
        private long killThresholdMs = DEFAULT_KILL_THRESHOLD_MS;
        private long sweepIntervalMs = DEFAULT_SWEEP_INTERVAL_MS;
        private SweeperMode mode = SweeperMode.WARN_ONLY;
        private boolean captureStackTrace = true;

        private Builder() {
        }

        public Builder warnThresholdMs(long warnThresholdMs) {
            this.warnThresholdMs = warnThresholdMs;
            return this;
        }

        public Builder killThresholdMs(long killThresholdMs) {
            this.killThresholdMs = killThresholdMs;
            return this;
        }

        public Builder sweepIntervalMs(long sweepIntervalMs) {
            this.sweepIntervalMs = sweepIntervalMs;
            return this;
        }

        public Builder mode(SweeperMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder captureStackTrace(boolean captureStackTrace) {
            this.captureStackTrace = captureStackTrace;
            return this;
        }

        public WardenConfig build() {
            return new WardenConfig(this);
        }
    }
}
