package com.umass.simulator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(@NotNull URI eventServiceBaseUrl,
                                  @Min(1) @Max(100) int failureEventCount,
                                  @NotNull Duration cascadeDelay,
                                  @NotNull Duration connectTimeout,
                                  @NotNull Duration readTimeout) {
    public SimulatorProperties {
        if (eventServiceBaseUrl != null && (eventServiceBaseUrl.getHost() == null
                || !("http".equalsIgnoreCase(eventServiceBaseUrl.getScheme())
                || "https".equalsIgnoreCase(eventServiceBaseUrl.getScheme())))) {
            throw new IllegalArgumentException("simulator.event-service-base-url must be an absolute HTTP(S) URL");
        }
        if (cascadeDelay != null && cascadeDelay.isNegative()) {
            throw new IllegalArgumentException("simulator.cascade-delay must not be negative");
        }
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())) {
            throw new IllegalArgumentException("simulator.connect-timeout must be positive");
        }
        if (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero())) {
            throw new IllegalArgumentException("simulator.read-timeout must be positive");
        }
    }
}
