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
@Table(name = "incidents")
public class Incident {
    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentSeverity severity;
    @Column(nullable = false, length = 100)
    private String rootService;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private Integer errorCount;

    protected Incident() {
    }

    public Incident(UUID id, String title, IncidentSeverity severity, String rootService,
                    Integer errorCount, Instant now) {
        this.id = id;
        this.title = title;
        this.status = IncidentStatus.OPEN;
        this.severity = severity;
        this.rootService = rootService;
        this.errorCount = errorCount;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public void updateStatus(IncidentStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public String getTitle() { return title; }
    public IncidentStatus getStatus() { return status; }
    public IncidentSeverity getSeverity() { return severity; }
    public String getRootService() { return rootService; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getErrorCount() { return errorCount; }
}
