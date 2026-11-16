package com.umass.incident.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "incident.correlation")
public record CorrelationProperties(@Min(1) int threshold, @Min(1) long windowSeconds) {
}
