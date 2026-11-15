package com.umass.incident.dto;

import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(UUID id, String title, IncidentStatus status,
                               IncidentSeverity severity, String rootService,
                               Instant createdAt, Instant updatedAt, Integer errorCount) {
    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(incident.getId(), incident.getTitle(), incident.getStatus(),
                incident.getSeverity(), incident.getRootService(), incident.getCreatedAt(),
                incident.getUpdatedAt(), incident.getErrorCount());
    }
}
