package com.umass.incident.service;

import com.umass.incident.config.HealthProperties;
import com.umass.incident.exception.ServiceNotFoundException;
import com.umass.incident.exception.InvalidServiceQueryException;
import com.umass.incident.model.HealthStatus;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.repository.IncidentEventRepository;
import com.umass.incident.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceOperationsServiceTest {
    @Mock private IncidentEventRepository events;
    @Mock private IncidentRepository incidents;
    @Mock private RedisRecentErrors recent;
    private ServiceOperationsService service;

    @BeforeEach
    void setUp() {
        service = new ServiceOperationsService(events, incidents, recent,
                new HealthStatusCalculator(new HealthProperties(2, 5)));
    }

    @Test
    void serviceKnownOnlyFromAnIncidentHasNoLastEventButCanBeDown() {
        when(events.existsByServiceName("orders")).thenReturn(false);
        when(incidents.existsByRootService("orders")).thenReturn(true);
        when(events.findFirstByServiceNameOrderByTimestampDescEventIdAsc("orders")).thenReturn(Optional.empty());
        when(incidents.findByRootServiceAndStatusIn(eq("orders"), anyList())).thenReturn(List.of(
                new Incident(UUID.randomUUID(), "Failure", IncidentSeverity.CRITICAL, "orders", 5, Instant.now())));

        var health = service.health("orders");

        assertThat(health.serviceName()).isEqualTo("orders");
        assertThat(health.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(health.errorsLastMinute()).isZero();
        assertThat(health.activeIncidentCount()).isEqualTo(1);
        assertThat(health.lastEventTimestamp()).isNull();
    }

    @Test
    void unknownServiceIsNotReportedAsHealthyOrReadFromRedis() {
        assertThatThrownBy(() -> service.health("missing")).isInstanceOf(ServiceNotFoundException.class);
        verifyNoInteractions(recent);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void invalidPaginationDoesNotReadOperationalStores(int page, int size) {
        assertThatThrownBy(() -> service.events("orders", page, size)).isInstanceOf(InvalidServiceQueryException.class);
        verifyNoInteractions(events, incidents, recent);
    }

    @Test
    void invalidServiceNameIsRejectedBeforeReadingStores() {
        assertThatThrownBy(() -> service.health(" ")).isInstanceOf(InvalidServiceQueryException.class);
        assertThatThrownBy(() -> service.health("a".repeat(101))).isInstanceOf(InvalidServiceQueryException.class);
        verifyNoInteractions(events, incidents, recent);
    }

    @Test
    void lowSeverityActiveIncidentDoesNotOverrideErrorBasedHealth() {
        when(events.existsByServiceName("orders")).thenReturn(true);
        when(events.findFirstByServiceNameOrderByTimestampDescEventIdAsc("orders")).thenReturn(Optional.empty());
        when(incidents.findByRootServiceAndStatusIn(eq("orders"), anyList())).thenReturn(List.of(
                new Incident(UUID.randomUUID(), "Minor", IncidentSeverity.LOW, "orders", 1, Instant.now())));
        when(recent.countLastMinute("orders")).thenReturn(2L);

        var health = service.health("orders");

        assertThat(health.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(health.activeIncidentCount()).isEqualTo(1);
    }
}
