package com.umass.event.dto;

import com.umass.event.model.EventSeverity;
import com.umass.event.model.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ServiceEvent(
        UUID eventId,
        @NotBlank @Size(max = 100) String serviceName,
        @NotNull EventType eventType,
        @NotNull EventSeverity severity,
        @NotBlank @Size(max = 4000) String message,
        @NotNull Instant timestamp,
        @Size(max = 128) String traceId,
        @Size(max = 50) Map<@NotBlank @Size(max = 100) String,
                @NotNull @Size(max = 1000) String> metadata) {
}
