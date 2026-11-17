package com.umass.simulator.dto;

import com.umass.simulator.model.EventSeverity;
import com.umass.simulator.model.EventType;
import java.time.Instant;
import java.util.UUID;

// Outbound HTTP contract. No Maven dependency on either application service.
public record ServiceEvent(UUID eventId, String serviceName, EventType eventType, EventSeverity severity,
                           String message, Instant timestamp, String traceId) {
}
