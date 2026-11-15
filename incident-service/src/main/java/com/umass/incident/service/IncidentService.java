package com.umass.incident.service;

import com.umass.incident.dto.CreateIncidentRequest;
import com.umass.incident.dto.IncidentResponse;
import com.umass.incident.exception.IncidentNotFoundException;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;

@Service
@Transactional
public class IncidentService {
    private final IncidentRepository repository;
    private final Clock clock;

    public IncidentService(IncidentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IncidentResponse createIncident(CreateIncidentRequest request) {
        Incident incident = new Incident(UUID.randomUUID(), request.title(), request.severity(),
                request.rootService(), request.errorCount(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        return IncidentResponse.from(repository.save(incident));
    }

    @Transactional(readOnly = true)
    public IncidentResponse getIncident(UUID id) {
        return IncidentResponse.from(findIncident(id));
    }

    public IncidentResponse updateStatus(UUID id, IncidentStatus status) {
        Incident incident = findIncident(id);
        incident.updateStatus(status, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return IncidentResponse.from(repository.save(incident));
    }

    private Incident findIncident(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> getIncidents(IncidentStatus status) {
        List<Incident> incidents = status == null ? repository.findAll() : repository.findByStatus(status);
        return incidents.stream().map(IncidentResponse::from).toList();
    }
}
