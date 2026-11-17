package com.umass.analysis.controller;

import com.umass.analysis.dto.AnalysisResponse;
import com.umass.analysis.service.IncidentAnalysisService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis/incidents")
public class AnalysisController {
    private final IncidentAnalysisService service;

    public AnalysisController(IncidentAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/{incidentId}")
    public AnalysisResponse analyze(@PathVariable String incidentId) {
        return service.analyze(incidentId);
    }
}
