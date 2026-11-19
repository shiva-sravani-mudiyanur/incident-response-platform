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

    @ParameterizedTest
    @ValueSource(strings = {"PT0.000000001S", "PT0.000999999S", "PT2147483.648S", "PT9223372036854775807S"})
    void connectionTimeoutMustFitBoundedMillisecondClient(String timeout) {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ZERO, Duration.parse(timeout), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simulator.connect-timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0.000000001S", "PT0.000999999S", "PT2147483.648S", "PT9223372036854775807S"})
    void readTimeoutMustFitBoundedMillisecondClient(String timeout) {
        assertThatThrownBy(() -> new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ZERO, Duration.ofSeconds(2), Duration.parse(timeout)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simulator.read-timeout");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2147483647})
    void millisecondTimeoutBoundsRemainValid(long milliseconds) {
        var timeout = Duration.ofMillis(milliseconds);
        var configured = new SimulatorProperties(URI.create("http://event-service.test"), 5,
                Duration.ZERO, timeout, timeout);
        assertThat(configured.connectTimeout()).isEqualTo(timeout);
        assertThat(configured.readTimeout()).isEqualTo(timeout);
    }

    private SimulatorProperties properties(int count) {
        return new SimulatorProperties(URI.create("http://event-service.test"), count,
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(15));
    }
}
