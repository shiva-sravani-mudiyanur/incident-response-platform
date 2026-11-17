package com.umass.incident.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthPropertiesTest {
    @Test
    void acceptsPositiveIncreasingThresholds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new HealthProperties(2, 5))).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"0,5", "2,0", "3,3", "5,2"})
    void rejectsNonpositiveOrNonIncreasingThresholds(int degraded, int down) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new HealthProperties(degraded, down))).isNotEmpty();
        }
    }
}
