package com.umass.incident.dto;

import com.umass.incident.model.HealthStatus;
import java.time.Instant;

public record ServiceHealthResponse(String serviceName, HealthStatus status, long errorsLastMinute,
                                    long activeIncidentCount, Instant lastEventTimestamp) {
}
