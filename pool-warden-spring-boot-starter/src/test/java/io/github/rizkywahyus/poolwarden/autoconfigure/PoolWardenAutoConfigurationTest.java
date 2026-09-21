package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.metrics.WardenMetrics;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.sweeper.PoolSweeper;
import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PoolWardenAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    PoolWardenAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:poolwarden;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void wrapsTheDataSourceAndStartsTheSweeper() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WardenDataSource.class);
            assertThat(context).hasSingleBean(ConnectionTracker.class);
            assertThat(context).hasSingleBean(PoolSweeper.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(WardenDataSource.class);
        });
    }

    @Test
    void doesNothingWhenDisabled() {
        runner.withPropertyValues("pool-warden.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PoolSweeper.class);
            assertThat(context.getBean(DataSource.class)).isNotInstanceOf(WardenDataSource.class);
        });
    }

    @Test
    void backsOffWhenThereIsNoDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PoolWardenAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PoolSweeper.class));
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues(
                "pool-warden.mode=REAP",
                "pool-warden.warn-threshold=5s",
                "pool-warden.kill-threshold=45s",
                "pool-warden.sweep-interval=1s",
                "pool-warden.capture-stack-trace=false").run(context -> {
            WardenConfig config = context.getBean(WardenConfig.class);

            assertThat(config.mode()).isEqualTo(SweeperMode.REAP);
            assertThat(config.warnThresholdMs()).isEqualTo(5_000L);
            assertThat(config.killThresholdMs()).isEqualTo(45_000L);
            assertThat(config.sweepIntervalMs()).isEqualTo(1_000L);
            assertThat(config.captureStackTrace()).isFalse();
        });
    }

    @Test
    void reportsALeakedConnectionEndToEnd() {
        runner.withBean(WardenMetrics.class, CollectingMetrics::new)
                .withPropertyValues(
                        "pool-warden.warn-threshold=100ms",
                        "pool-warden.sweep-interval=50ms")
                .run(context -> {
                    DataSource dataSource = context.getBean(DataSource.class);
                    CollectingMetrics metrics = context.getBean(CollectingMetrics.class);

                    Connection leaked = dataSource.getConnection();
                    assertThat(leaked.isClosed()).isFalse();

                    await().atMost(Duration.ofSeconds(5))
                            .untilAsserted(() -> assertThat(metrics.warnedAges()).hasSize(1));

                    // Still held: WARN_ONLY never touches the connection.
                    assertThat(leaked.isClosed()).isFalse();
                    assertThat(context.getBean(ConnectionTracker.class).size()).isEqualTo(1);

                    leaked.close();
                    assertThat(context.getBean(ConnectionTracker.class).size()).isZero();
                });
    }

    /** Captures warden events so the test can assert on them. */
    static class CollectingMetrics implements WardenMetrics {

        private final List<Long> warnedAges = new CopyOnWriteArrayList<>();
        private final List<String> warnedDataSources = new CopyOnWriteArrayList<>();

        @Override
        public void leakWarned(String dataSourceName, long ageMillis) {
            warnedDataSources.add(dataSourceName);
            warnedAges.add(ageMillis);
        }

        @Override
        public void connectionReaped(String dataSourceName, long ageMillis) {
            // not used in WARN_ONLY mode
        }

        List<Long> warnedAges() {
            return List.copyOf(warnedAges);
        }

        List<String> warnedDataSources() {
            return List.copyOf(warnedDataSources);
        }
    }
}
