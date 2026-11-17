package com.umass.incident.service;

import com.umass.incident.config.HealthProperties;
import com.umass.incident.model.HealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStatusCalculatorTest {
    private final HealthStatusCalculator calculator = new HealthStatusCalculator(new HealthProperties(2, 5));

    @ParameterizedTest
    @CsvSource({"0,HEALTHY", "1,HEALTHY", "2,DEGRADED", "4,DEGRADED", "5,DOWN", "100,DOWN"})
    void appliesErrorThresholdBoundaries(long errors, HealthStatus expected) {
        assertThat(calculator.calculate(errors, false)).isEqualTo(expected);
    }

    @Test
    void activeSevereIncidentOverridesAnEmptyErrorWindow() {
        assertThat(calculator.calculate(0, true)).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void thresholdsAreConfigurableIndependentlyOfIncidentDetection() {
        var custom = new HealthStatusCalculator(new HealthProperties(3, 7));
        assertThat(custom.calculate(2, false)).isEqualTo(HealthStatus.HEALTHY);
        assertThat(custom.calculate(3, false)).isEqualTo(HealthStatus.DEGRADED);
        assertThat(custom.calculate(6, false)).isEqualTo(HealthStatus.DEGRADED);
        assertThat(custom.calculate(7, false)).isEqualTo(HealthStatus.DOWN);
    }
}
