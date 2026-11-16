package com.umass.incident.repository;

import com.umass.incident.model.IncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {
}
