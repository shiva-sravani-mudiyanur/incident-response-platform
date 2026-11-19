package com.umass.incident.repository;

import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 3,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    long countByStatusIn(List<IncidentStatus> statuses);

    List<Incident> findByStatus(IncidentStatus status);
    boolean existsByRootServiceAndStatusIn(String rootService, List<IncidentStatus> statuses);
    boolean existsByRootService(String rootService);
    List<Incident> findByRootServiceAndStatusIn(String rootService, List<IncidentStatus> statuses);
}
