package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.metrics.NoOpWardenMetrics;
import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.sweeper.PoolSweeper;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.autoconfigure.actuate.PoolWardenEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires the warden into any application that has a {@link DataSource}.
 *
 * <p>The condition is deliberately on {@code DataSource} rather than on a specific pool class,
 * so the starter works on HikariCP, Tomcat JDBC, DBCP2 or a hand-built data source alike.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
        // Referenced by name because Actuator is optional: the warden must be configured after the
        // meter registry exists, or it would resolve its metrics sink while there is none and stay
        // on the no-op implementation for the life of the application.
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "pool-warden", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(PoolWardenProperties.class)
public class PoolWardenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WardenConfig wardenConfig(PoolWardenProperties properties) {
        return properties.toWardenConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectionTracker poolWardenConnectionTracker() {
        return new ConnectionTracker();
    }

    /**
     * Uses Micrometer when the application has a registry and falls back to a no-op sink
     * otherwise. Resolved in one bean method rather than through two conditional beans so the
     * choice does not depend on configuration-class ordering.
     */
    @Bean
    @ConditionalOnMissingBean
    public WardenMetrics wardenMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return NoOpWardenMetrics.INSTANCE;
        }
        return new MicrometerWardenMetrics(registry);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public PoolSweeper poolSweeper(ConnectionTracker tracker, WardenConfig config, WardenMetrics metrics) {
        return new PoolSweeper(tracker, config, metrics);
    }

    /** Registered only when Spring Boot Actuator is on the classpath and the endpoint is exposed. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class PoolWardenEndpointConfiguration {

        @Bean
        @ConditionalOnAvailableEndpoint
        @ConditionalOnMissingBean
        PoolWardenEndpoint poolWardenEndpoint(ConnectionTracker tracker, WardenConfig config) {
            return new PoolWardenEndpoint(tracker, config);
        }
    }

    /**
     * Static so the container can create the post-processor without instantiating this
     * configuration class first.
     */
    @Bean
    public static PoolWardenBeanPostProcessor poolWardenBeanPostProcessor(
            ObjectProvider<ConnectionTracker> trackerProvider, ObjectProvider<WardenConfig> configProvider) {
        return new PoolWardenBeanPostProcessor(trackerProvider, configProvider);
    }

    /**
     * Publishes the per-data-source connection gauges after the context is built, when the meter
     * registry exists. See {@link PoolWardenMetricsBinder}.
     */
    @Bean
    @ConditionalOnMissingBean
    public PoolWardenMetricsBinder poolWardenMetricsBinder(ObjectProvider<WardenDataSource> dataSources,
                                                           ObjectProvider<WardenMetrics> metricsProvider,
                                                           ConnectionTracker tracker) {
        return new PoolWardenMetricsBinder(dataSources, metricsProvider, tracker);
    }
}
