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
        // SimpleClientHttpRequestFactory truncates Duration to an int millisecond timeout;
        // zero means unbounded, and larger values cannot be represented safely.
        if (connectTimeout != null && (connectTimeout.compareTo(Duration.ofMillis(1)) < 0
                || connectTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0)) {
            throw new IllegalArgumentException("simulator.connect-timeout must be between 1ms and 2147483647ms");
        }
        if (readTimeout != null && (readTimeout.compareTo(Duration.ofMillis(1)) < 0
                || readTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0)) {
            throw new IllegalArgumentException("simulator.read-timeout must be between 1ms and 2147483647ms");
        }
    }
}
