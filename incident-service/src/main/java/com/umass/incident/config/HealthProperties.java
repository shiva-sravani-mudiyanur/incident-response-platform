package com.umass.incident.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "incident.health")
public record HealthProperties(@DefaultValue("2") @Min(1) int degradedThreshold,
                               @DefaultValue("5") @Min(1) int downThreshold) {
    @AssertTrue(message = "down-threshold must be greater than degraded-threshold")
    public boolean isThresholdOrderValid() {
        return downThreshold > degradedThreshold;
    }
}
