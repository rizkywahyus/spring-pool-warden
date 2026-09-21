package io.github.rizkywahyus.poolwarden.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import io.github.rizkywahyus.poolwarden.autoconfigure.actuate.PoolWardenEndpoint;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application with more than one data source has to stay legible: each pool reports under its
 * own bean name, and a data source that merely forwards to another one must not be counted twice.
 */
class PoolWardenMultipleDataSourcesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PoolWardenAutoConfiguration.class));

    @Test
    void reportsEachPoolUnderItsOwnBeanName() {
        runner.withUserConfiguration(TwoPools.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("management.endpoints.web.exposure.include=*")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    DataSource orders = context.getBean("ordersDataSource", DataSource.class);
                    DataSource reporting = context.getBean("reportingDataSource", DataSource.class);
                    assertThat(orders).isInstanceOf(WardenDataSource.class);
                    assertThat(reporting).isInstanceOf(WardenDataSource.class);

                    try (Connection ordersConnection = orders.getConnection()) {
                        assertThat(ordersConnection).isNotNull();

                        assertThat(activeGauge(registry, "ordersDataSource")).isEqualTo(1.0);
                        assertThat(activeGauge(registry, "reportingDataSource")).isZero();

                        PoolWardenEndpoint endpoint = context.getBean(PoolWardenEndpoint.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> connections =
                                (List<Map<String, Object>>) endpoint.activeConnections().get("connections");
                        assertThat(connections).singleElement()
                                .extracting(entry -> entry.get("dataSource"))
                                .isEqualTo("ordersDataSource");
                    }
                });
    }

    @Test
    void tracksACheckoutOnceWhenADataSourceDelegatesToAnother() {
        runner.withUserConfiguration(PoolBehindALazyProxy.class).run(context -> {
            // The pool is wrapped; the proxy in front of it is left alone, so a checkout through
            // the proxy passes through exactly one warden.
            assertThat(context.getBean("ordersDataSource", DataSource.class))
                    .isInstanceOf(WardenDataSource.class);
            assertThat(context.getBean("lazyDataSource", DataSource.class))
                    .isNotInstanceOf(WardenDataSource.class);

            ConnectionTracker tracker = context.getBean(ConnectionTracker.class);
            DataSource lazy = context.getBean("lazyDataSource", DataSource.class);

            try (Connection connection = lazy.getConnection()) {
                // LazyConnectionDataSourceProxy only borrows once a statement is actually run.
                connection.createStatement().close();
                assertThat(tracker.size()).isEqualTo(1);
                assertThat(tracker.snapshot().get(0).dataSourceName()).isEqualTo("ordersDataSource");
            }

            assertThat(tracker.size()).isZero();
        });
    }

    private static double activeGauge(MeterRegistry registry, String dataSourceName) {
        return registry.get("pool.warden.connections.active")
                .tag("datasource", dataSourceName)
                .gauge().value();
    }

    private static HikariDataSource pool(String databaseName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoPools {

        @Bean(destroyMethod = "close")
        DataSource ordersDataSource() {
            return pool("poolwarden-orders");
        }

        @Bean(destroyMethod = "close")
        DataSource reportingDataSource() {
            return pool("poolwarden-reporting");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PoolBehindALazyProxy {

        @Bean(destroyMethod = "close")
        DataSource ordersDataSource() {
            return pool("poolwarden-lazy");
        }

        @Bean
        DataSource lazyDataSource(DataSource ordersDataSource) {
            LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(ordersDataSource);
            // Without this the proxy opens a connection at startup just to read the default.
            proxy.setDefaultAutoCommit(true);
            return proxy;
        }
    }
}
