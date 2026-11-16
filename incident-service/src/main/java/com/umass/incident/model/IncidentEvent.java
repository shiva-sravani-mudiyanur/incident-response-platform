package com.umass.incident.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_events")
public class IncidentEvent {
    @Id
    @Column(name = "event_id")
    private UUID eventId;
    @Column(nullable = false, length = 100)
    private String serviceName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventSeverity severity;
    @Column(nullable = false, length = 4000)
    private String message;
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
    @Column(length = 128)
    private String traceId;
    @Column(nullable = false)
    private Instant processedAt;

    protected IncidentEvent() {
    }

    public IncidentEvent(UUID eventId, String serviceName, EventType eventType, EventSeverity severity,
                         String message, Instant timestamp, String traceId, Instant processedAt) {
        this.eventId = eventId;
        this.serviceName = serviceName;
        this.eventType = eventType;
        this.severity = severity;
        this.message = message;
        this.timestamp = timestamp;
        this.traceId = traceId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() { return eventId; }
    public String getServiceName() { return serviceName; }
    public EventType getEventType() { return eventType; }
    public EventSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }
    public String getTraceId() { return traceId; }
    public Instant getProcessedAt() { return processedAt; }
}
