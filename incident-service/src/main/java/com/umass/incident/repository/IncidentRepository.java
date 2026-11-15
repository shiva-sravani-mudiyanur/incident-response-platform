package com.umass.incident.repository;

import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    List<Incident> findByStatus(IncidentStatus status);
}
