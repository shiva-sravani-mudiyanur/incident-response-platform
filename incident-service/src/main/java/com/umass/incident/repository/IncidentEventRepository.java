package com.umass.incident.repository;

import com.umass.incident.model.IncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {
    boolean existsByServiceName(String serviceName);
    Optional<IncidentEvent> findFirstByServiceNameOrderByTimestampDescEventIdAsc(String serviceName);
    Page<IncidentEvent> findByServiceName(String serviceName, Pageable pageable);

    @Query(value = """
            SELECT service_name FROM incident_events
            UNION
            SELECT root_service FROM incidents
            ORDER BY 1
            """, nativeQuery = true)
    List<String> findKnownServiceNames();

    // Returns 1 for a new event, 0 for an existing eventId. PostgreSQL resolves
    // concurrent conflicts before correlation runs; never overwrite the original row.
    @Modifying
    @Query(value = """
            INSERT INTO incident_events
                (event_id, service_name, event_type, severity, message, timestamp, trace_id, processed_at)
            VALUES (:eventId, :serviceName, :eventType, :severity, :message, :timestamp, :traceId, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId,
                       @Param("serviceName") String serviceName,
                       @Param("eventType") String eventType,
                       @Param("severity") String severity,
                       @Param("message") String message,
                       @Param("timestamp") Instant timestamp,
                       @Param("traceId") String traceId,
                       @Param("processedAt") Instant processedAt);
}
