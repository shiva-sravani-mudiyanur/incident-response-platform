package com.umass.diagnostics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("diagnostics.resilience")
public record DiagnosticsResilienceProperties(
        @DefaultValue("8s") Duration operationTimeout,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("100ms") Duration retryDelay,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration waitDurationInOpenState,
        @DefaultValue("1") int permittedCallsInHalfOpenState,
        @DefaultValue("4") int maxConcurrentCalls) {
    public DiagnosticsResilienceProperties {
        duration(operationTimeout, Duration.ofSeconds(30), false, "operation-timeout");
        duration(retryDelay, Duration.ofSeconds(2), true, "retry-delay");
        duration(waitDurationInOpenState, Duration.ofMinutes(5), false, "wait-duration-in-open-state");
        range(maxAttempts, 1, 3, "max-attempts");
        range(slidingWindowSize, 1, 100, "sliding-window-size");
        range(minimumNumberOfCalls, 1, slidingWindowSize, "minimum-number-of-calls");
        range(permittedCallsInHalfOpenState, 1, 32, "permitted-calls-in-half-open-state");
        range(maxConcurrentCalls, 1, 32, "max-concurrent-calls");
        if (!Float.isFinite(failureRateThreshold) || failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException("failure-rate-threshold must be greater than 0 and at most 100");
        }
    }

    private static void range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is outside its permitted range");
    }

    private static void duration(Duration value, Duration maximum, boolean zeroAllowed, String name) {
        if (value == null || value.isNegative() || (!zeroAllowed && value.isZero())
                || value.compareTo(maximum) > 0 || (!value.isZero() && value.compareTo(Duration.ofMillis(1)) < 0)) {
            throw new IllegalArgumentException(name + " is outside its permitted duration range");
        }
    }
}
