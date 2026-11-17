package com.umass.simulator.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatorPropertiesTest {
    @Test
    void validConfigurationPassesValidation() {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(properties(5))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void failureVolumeMustBePositiveAndBounded(int count) {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(properties(count))).isNotEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/events", "/relative", "ftp://event-service.test"})
    void downstreamUrlMustBeAbsoluteHttpOrHttps(String url) {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create(url), 5, Duration.ZERO,
                Duration.ofSeconds(2), Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCascadeDelayIsRejected() {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ofMillis(-1), Duration.ofSeconds(2), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroConnectionTimeoutIsRejected() {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroReadTimeoutIsRejected() {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private SimulatorProperties properties(int count) {
        return new SimulatorProperties(URI.create("http://event-service.test"), count,
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(15));
    }
}
