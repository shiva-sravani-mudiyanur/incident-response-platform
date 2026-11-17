package com.umass.incident.repository;

import com.umass.incident.model.IncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {
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
