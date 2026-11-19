package com.umass.incident.service;

import com.umass.incident.dto.CreateIncidentRequest;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    @Mock
    private IncidentRepository repository;
    private IncidentService service;
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private com.umass.incident.observability.IncidentMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new com.umass.incident.observability.IncidentMetrics(registry, repository);
        service = new IncidentService(repository, Clock.fixed(NOW, ZoneOffset.UTC), metrics);
    }

    @Test
    void manualCreateIsCountedOnlyWhenItsTransactionCommits() {
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.createIncident(new CreateIncidentRequest("not logged", IncidentSeverity.HIGH, "payment-service", 5));
            assertThat(registry.get("incident.incidents.created").tag("source", "manual").counter().count()).isZero();
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(registry.get("incident.incidents.created").tag("source", "manual").counter().count()).isEqualTo(1);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }

    @Test
    void createIncidentSetsServerOwnedFieldsAndPersists() {
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createIncident(new CreateIncidentRequest(
                "Payment failures", IncidentSeverity.HIGH, "payment-service", 5));

        assertThat(response.id()).isNotNull();
        assertThat(response.title()).isEqualTo("Payment failures");
        assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(response.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(response.rootService()).isEqualTo("payment-service");
        assertThat(response.errorCount()).isEqualTo(5);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void retrieveIncident() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new Incident(id,
                "Payment failures", IncidentSeverity.HIGH, "payment-service", 5, NOW)));

        var response = service.getIncident(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.title()).isEqualTo("Payment failures");
    }

    @Test
    void incidentNotFoundIncludesRequestedId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIncident(id))
                .hasMessage("Incident not found: " + id)
                .satisfies(error -> assertThat(error.getClass().getSimpleName())
                        .isEqualTo("IncidentNotFoundException"));
    }

    @Test
    void updateIncidentStatusPreservesIdentityAndCreationTime() {
        UUID id = UUID.randomUUID();
        Incident incident = new Incident(id, "Failure", IncidentSeverity.HIGH,
                "payment-service", 5, NOW.minusSeconds(60));
        when(repository.findById(id)).thenReturn(Optional.of(incident));
        when(repository.save(incident)).thenReturn(incident);

        var response = service.updateStatus(id, IncidentStatus.INVESTIGATING);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(response.createdAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(response.updatedAt()).isEqualTo(NOW);
        assertThat(response.errorCount()).isEqualTo(5);
        verify(repository).save(incident);
    }

    @Test
    void filterByStatus() {
        Incident incident = new Incident(UUID.randomUUID(), "Failure", IncidentSeverity.HIGH,
                "payment-service", 5, NOW);
        when(repository.findByStatus(IncidentStatus.OPEN)).thenReturn(List.of(incident));

        var responses = service.getIncidents(IncidentStatus.OPEN);

        assertThat(responses).extracting(response -> response.id()).containsExactly(incident.getId());
        verify(repository, never()).findAll();
    }

    @Test
    void listAllWithoutStatusFilter() {
        Incident incident = new Incident(UUID.randomUUID(), "Failure", IncidentSeverity.LOW,
                "inventory-service", 0, NOW);
        when(repository.findAll()).thenReturn(List.of(incident));

        assertThat(service.getIncidents(null)).extracting(response -> response.id())
                .containsExactly(incident.getId());
        verify(repository, never()).findByStatus(any());
    }

    @Test
    void updateMissingIncidentDoesNotWrite() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(id, IncidentStatus.RESOLVED))
                .hasMessage("Incident not found: " + id);
        verify(repository, never()).save(any());
    }

    @Test
    void timestampsUsePostgresqlMicrosecondPrecision() {
        Instant preciseNow = Instant.parse("2026-11-15T12:00:00.123456789Z");
        Instant databaseNow = Instant.parse("2026-11-15T12:00:00.123456Z");
        service = new IncidentService(repository, Clock.fixed(preciseNow, ZoneOffset.UTC), metrics);
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createIncident(new CreateIncidentRequest("Failure",
                IncidentSeverity.HIGH, "payment-service", 5));
        assertThat(created.createdAt()).isEqualTo(databaseNow);
        assertThat(created.updatedAt()).isEqualTo(databaseNow);

        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new Incident(id, "Failure",
                IncidentSeverity.HIGH, "payment-service", 5, NOW)));
        assertThat(service.updateStatus(id, IncidentStatus.RESOLVED).updatedAt()).isEqualTo(databaseNow);
    }
}
