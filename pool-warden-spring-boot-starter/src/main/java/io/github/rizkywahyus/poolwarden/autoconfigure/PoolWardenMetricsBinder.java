package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers one live connection count per wrapped data source, once the context is fully built.
 *
 * <p>The timing is the point. Data sources are wrapped by a bean post-processor, which runs very
 * early -- long before the meter registry exists. Asking for {@link WardenMetrics} from there
 * would resolve it while there is still no registry to find, fixing the application on the no-op
 * sink for the rest of its life. Waiting until every singleton is in place means the real registry
 * is available, and the wrapped data sources are all there to be counted.
 */
class PoolWardenMetricsBinder implements SmartInitializingSingleton {

    private final ObjectProvider<WardenDataSource> dataSources;
    private final ObjectProvider<WardenMetrics> metricsProvider;
    private final ConnectionTracker tracker;

    PoolWardenMetricsBinder(ObjectProvider<WardenDataSource> dataSources,
                            ObjectProvider<WardenMetrics> metricsProvider,
                            ConnectionTracker tracker) {
        this.dataSources = dataSources;
        this.metricsProvider = metricsProvider;
        this.tracker = tracker;
    }

    @Override
    public void afterSingletonsInstantiated() {
        WardenMetrics metrics = metricsProvider.getObject();
        dataSources.orderedStream().forEach(dataSource -> {
            String name = dataSource.name();
            metrics.dataSourceRegistered(name, () -> tracker.size(name));
        });
    }
}
