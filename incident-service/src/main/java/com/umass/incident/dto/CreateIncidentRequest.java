package com.umass.incident.dto;

import com.umass.incident.model.IncidentSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL") String title,
        @NotNull IncidentSeverity severity,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL") String rootService,
        @NotNull @PositiveOrZero Integer errorCount) {
}
