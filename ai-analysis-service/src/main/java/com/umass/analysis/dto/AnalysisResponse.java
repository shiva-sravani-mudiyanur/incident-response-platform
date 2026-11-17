package com.umass.analysis.dto;

import java.util.List;

public record AnalysisResponse(String incidentId, String probableCause, Confidence confidence,
                               List<Evidence> evidence, List<String> recommendedActions, List<String> limitations) {
    public AnalysisResponse {
        evidence = List.copyOf(evidence);
        recommendedActions = List.copyOf(recommendedActions);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
