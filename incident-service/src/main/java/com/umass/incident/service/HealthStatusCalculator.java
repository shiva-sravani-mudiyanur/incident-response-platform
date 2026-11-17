package com.umass.incident.service;

import com.umass.incident.config.HealthProperties;
import com.umass.incident.model.HealthStatus;
import org.springframework.stereotype.Component;

@Component
public class HealthStatusCalculator {
    private final HealthProperties properties;

    public HealthStatusCalculator(HealthProperties properties) {
        this.properties = properties;
    }

    public HealthStatus calculate(long errorsLastMinute, boolean activeSevereIncident) {
        if (activeSevereIncident || errorsLastMinute >= properties.downThreshold()) return HealthStatus.DOWN;
        if (errorsLastMinute >= properties.degradedThreshold()) return HealthStatus.DEGRADED;
        return HealthStatus.HEALTHY;
    }
}
