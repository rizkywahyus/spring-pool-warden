package io.github.rizkywahyus.poolwarden.core.sweeper;

/**
 * What the sweeper does when a tracked connection exceeds its threshold.
 */
public enum SweeperMode {

    /** Log a warning and emit a metric. The connection is left untouched. */
    WARN_ONLY,

    /** Warn first, then force-reclaim the connection once the kill threshold is exceeded. */
    REAP
}
