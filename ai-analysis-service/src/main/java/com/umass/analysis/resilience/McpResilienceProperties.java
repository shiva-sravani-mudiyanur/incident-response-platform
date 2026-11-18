package com.umass.analysis.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("analysis.resilience")
public record McpResilienceProperties(
        @DefaultValue("20s") Duration operationTimeout,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("100ms") Duration retryDelay,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration waitDurationInOpenState,
        @DefaultValue("1") int permittedCallsInHalfOpenState,
        @DefaultValue("4") int maxConcurrentCalls) {
    public McpResilienceProperties {
        if (operationTimeout == null || operationTimeout.compareTo(Duration.ofMillis(1)) < 0
                || operationTimeout.compareTo(Duration.ofMinutes(2)) > 0
                || maxAttempts < 1 || maxAttempts > 3
                || retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(1)) > 0
                || slidingWindowSize < 2 || slidingWindowSize > 100
                || minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize
                || !Float.isFinite(failureRateThreshold) || failureRateThreshold < 1 || failureRateThreshold > 100
                || waitDurationInOpenState == null || waitDurationInOpenState.compareTo(Duration.ofMillis(1)) < 0
                || waitDurationInOpenState.compareTo(Duration.ofMinutes(5)) > 0
                || permittedCallsInHalfOpenState < 1 || permittedCallsInHalfOpenState > 10
                || maxConcurrentCalls < 1 || maxConcurrentCalls > 32) {
            throw new IllegalArgumentException("MCP resilience requires bounded positive deadlines, attempts, circuit settings, and concurrency.");
        }
    }
}
