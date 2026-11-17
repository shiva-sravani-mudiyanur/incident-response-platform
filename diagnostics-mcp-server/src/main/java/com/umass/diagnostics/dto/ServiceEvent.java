package com.umass.diagnostics.dto;
import java.time.Instant;
public record ServiceEvent(String eventId, String serviceName, String eventType, String severity,
                           String message, Instant timestamp, String traceId, Instant processedAt) { }
