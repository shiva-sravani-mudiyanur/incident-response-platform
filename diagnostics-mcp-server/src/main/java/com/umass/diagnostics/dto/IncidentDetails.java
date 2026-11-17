package com.umass.diagnostics.dto;
import java.time.Instant;
public record IncidentDetails(String id, String title, String status, String severity, String rootService,
                              Instant createdAt, Instant updatedAt, Integer errorCount) { }
