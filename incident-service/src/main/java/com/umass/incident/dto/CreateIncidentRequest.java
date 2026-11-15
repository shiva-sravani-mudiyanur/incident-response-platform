package com.umass.incident.dto;

import com.umass.incident.model.IncidentSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull IncidentSeverity severity,
        @NotBlank @Size(max = 100) String rootService,
        @NotNull @PositiveOrZero Integer errorCount) {
}
