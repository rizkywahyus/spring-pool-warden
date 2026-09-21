package io.github.rizkywahyus.poolwarden.core.config;

import io.github.rizkywahyus.poolwarden.core.sweeper.SweeperMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WardenConfigTest {

    @Test
    void defaultsAreWarnOnly() {
        WardenConfig config = WardenConfig.defaults();

        assertThat(config.mode()).isEqualTo(SweeperMode.WARN_ONLY);
        assertThat(config.warnThresholdMs()).isEqualTo(30_000L);
        assertThat(config.sweepIntervalMs()).isEqualTo(10_000L);
        assertThat(config.captureStackTrace()).isTrue();
    }

    @Test
    void rejectsNonPositiveThresholds() {
        assertThatThrownBy(() -> WardenConfig.builder().warnThresholdMs(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warnThresholdMs");

        assertThatThrownBy(() -> WardenConfig.builder().sweepIntervalMs(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sweepIntervalMs");
    }

    @Test
    void reapModeRequiresKillThresholdAboveWarnThreshold() {
        assertThatThrownBy(() -> WardenConfig.builder()
                .mode(SweeperMode.REAP)
                .warnThresholdMs(30_000)
                .killThresholdMs(30_000)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be greater than warnThresholdMs");
    }

    @Test
    void warnOnlyModeIgnoresKillThreshold() {
        WardenConfig config = WardenConfig.builder().killThresholdMs(-1).build();

        assertThat(config.mode()).isEqualTo(SweeperMode.WARN_ONLY);
    }
}
