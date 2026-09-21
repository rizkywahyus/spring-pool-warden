package io.github.rizkywahyus.poolwarden.core.metrics;

/** Default sink used when no metrics backend is available. */
public final class NoOpWardenMetrics implements WardenMetrics {

    public static final NoOpWardenMetrics INSTANCE = new NoOpWardenMetrics();

    private NoOpWardenMetrics() {
    }

    @Override
    public void leakWarned(String dataSourceName, long ageMillis) {
        // nothing to record
    }

    @Override
    public void connectionReaped(String dataSourceName, long ageMillis) {
        // nothing to record
    }
}
