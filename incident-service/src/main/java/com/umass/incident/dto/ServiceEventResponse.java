package com.umass.incident.dto;

import com.umass.incident.model.IncidentEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import java.time.Instant;
import java.util.UUID;

public record ServiceEventResponse(UUID eventId, String serviceName, EventType eventType, EventSeverity severity,
                                   String message, Instant timestamp, String traceId, Instant processedAt) {
    public static ServiceEventResponse from(IncidentEvent event) {
        return new ServiceEventResponse(event.getEventId(), event.getServiceName(), event.getEventType(),
                event.getSeverity(), event.getMessage(), event.getTimestamp(), event.getTraceId(), event.getProcessedAt());
    }
}
