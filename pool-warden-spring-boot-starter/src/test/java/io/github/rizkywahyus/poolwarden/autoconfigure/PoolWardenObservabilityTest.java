package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.autoconfigure.actuate.PoolWardenEndpoint;
import io.github.rizkywahyus.poolwarden.core.metrics.NoOpWardenMetrics;
import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PoolWardenObservabilityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    PoolWardenAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:poolwarden-observability;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void usesTheRegistryThatAutoConfigurationProvides() {
        // The registry a real application has comes from auto-configuration, not from a bean
        // registered before everything else. Resolving the metrics sink too early -- while
        // wrapping data sources, say -- would find no registry and pin the application to the
        // no-op sink even though Micrometer is right there.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        DataSourceAutoConfiguration.class,
                        PoolWardenAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:poolwarden-autoconfigured-registry;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertThat(context.getBean(WardenMetrics.class))
                            .isInstanceOf(MicrometerWardenMetrics.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("pool.warden.connections.active")
                            .tag("datasource", "dataSource").gauge().value()).isZero();
                });
    }

    @Test
    void fallsBackToNoOpMetricsWithoutAMeterRegistry() {
        runner.run(context -> assertThat(context.getBean(WardenMetrics.class))
                .isSameAs(NoOpWardenMetrics.INSTANCE));
    }

    @Test
    void publishesMetersWhenAMeterRegistryIsPresent() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "pool-warden.warn-threshold=100ms",
                        "pool-warden.sweep-interval=50ms")
                .run(context -> {
                    assertThat(context.getBean(WardenMetrics.class)).isInstanceOf(MicrometerWardenMetrics.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    DataSource dataSource = context.getBean(DataSource.class);

                    try (Connection leaked = dataSource.getConnection()) {
                        // Every meter carries the bean name of the pool it belongs to.
                        assertThat(registry.get("pool.warden.connections.active")
                                .tag("datasource", "dataSource").gauge().value()).isEqualTo(1.0);
                        assertThat(leaked).isNotNull();

                        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                                assertThat(registry.get("pool.warden.leaks.warned")
                                        .tag("datasource", "dataSource").counter().count()).isEqualTo(1.0));
                        assertThat(registry.get("pool.warden.leaks.age")
                                .tag("datasource", "dataSource").tag("event", "warned")
                                .timer().count()).isEqualTo(1L);
                    }

                    assertThat(registry.get("pool.warden.connections.active")
                            .tag("datasource", "dataSource").gauge().value()).isZero();
                });
    }

    @Test
    void exposesTheActuatorEndpointWithTheOldestConnectionFirst() {
        runner.withPropertyValues("management.endpoints.web.exposure.include=*").run(context -> {
            PoolWardenEndpoint endpoint = context.getBean(PoolWardenEndpoint.class);
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection first = dataSource.getConnection();
                 Connection second = dataSource.getConnection()) {
                assertThat(first).isNotSameAs(second);

                Map<String, Object> report = endpoint.activeConnections();

                assertThat(report.get("activeConnections")).isEqualTo(2);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> connections = (List<Map<String, Object>>) report.get("connections");
                assertThat((Long) connections.get(0).get("ageMs"))
                        .isGreaterThanOrEqualTo((Long) connections.get(1).get("ageMs"));
                assertThat(connections.get(0)).containsKeys("id", "dataSource", "thread", "warned", "callSite");
                assertThat(connections.get(0).get("dataSource")).isEqualTo("dataSource");
            }

            assertThat(endpoint.activeConnections().get("activeConnections")).isEqualTo(0);
        });
    }
}
