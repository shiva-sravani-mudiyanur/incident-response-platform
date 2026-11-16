package com.umass.incident.dto;

import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// Consumer-owned JSON contract; no dependency on event-service Java classes.
public record ServiceEvent(
        @NotNull UUID eventId,
        @NotBlank @Size(max = 100) String serviceName,
        @NotNull EventType eventType,
        @NotNull EventSeverity severity,
        @NotBlank @Size(max = 4000) String message,
        @NotNull Instant timestamp,
        @Size(max = 128) String traceId,
        @Size(max = 50) Map<@NotBlank @Size(max = 100) String,
                @NotNull @Size(max = 1000) String> metadata) {
}
