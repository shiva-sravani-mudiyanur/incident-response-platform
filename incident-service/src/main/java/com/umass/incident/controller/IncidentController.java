package com.umass.incident.controller;

import com.umass.incident.dto.CreateIncidentRequest;
import com.umass.incident.dto.IncidentResponse;
import com.umass.incident.dto.UpdateIncidentStatusRequest;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.service.IncidentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {
    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentResponse response = service.createIncident(request);
        return ResponseEntity.created(URI.create("/api/incidents/" + response.id())).body(response);
    }

    @GetMapping
    public List<IncidentResponse> list(@RequestParam(required = false) IncidentStatus status) {
        return service.getIncidents(status);
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable UUID id) {
        return service.getIncident(id);
    }

    @PatchMapping("/{id}/status")
    public IncidentResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateIncidentStatusRequest request) {
        return service.updateStatus(id, request.status());
    }
}
