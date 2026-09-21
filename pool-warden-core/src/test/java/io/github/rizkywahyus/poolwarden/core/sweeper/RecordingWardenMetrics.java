package io.github.rizkywahyus.poolwarden.core.sweeper;

import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test double that keeps every event it is handed. */
final class RecordingWardenMetrics implements WardenMetrics {

    private final List<Long> warnedAges = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> reapedAges = Collections.synchronizedList(new ArrayList<>());
    private final List<String> dataSourceNames = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void leakWarned(String dataSourceName, long ageMillis) {
        dataSourceNames.add(dataSourceName);
        warnedAges.add(ageMillis);
    }

    @Override
    public void connectionReaped(String dataSourceName, long ageMillis) {
        dataSourceNames.add(dataSourceName);
        reapedAges.add(ageMillis);
    }

    List<String> dataSourceNames() {
        return List.copyOf(dataSourceNames);
    }

    List<Long> warnedAges() {
        return List.copyOf(warnedAges);
    }

    List<Long> reapedAges() {
        return List.copyOf(reapedAges);
    }
}
