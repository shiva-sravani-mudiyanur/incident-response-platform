package com.umass.incident.service;

import com.umass.incident.config.CorrelationProperties;
import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorrelationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    @Mock private RedisFailureCounter counter;
    @Mock private IncidentRepository repository;
    @Mock private JdbcTemplate jdbc;
    private CorrelationService service;

    @BeforeEach
    void setUp() {
        service = new CorrelationService(counter, repository, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                new CorrelationProperties(5, 60));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 4})
    void fewerThanFiveErrorsDoNotCreateIncident(long count) {
        when(counter.record(any())).thenReturn(new RedisFailureCounter.WindowSnapshot(count, false));

        service.correlate(event("order-service", EventSeverity.ERROR));

        verifyNoInteractions(repository, jdbc);
    }

    @Test
    void fifthErrorCreatesDeterministicOpenHighSeverityIncident() {
        when(counter.record(any())).thenReturn(new RedisFailureCounter.WindowSnapshot(5, false));

        service.correlate(event("order-service", EventSeverity.ERROR));

        var captor = ArgumentCaptor.forClass(Incident.class);
        verify(repository).save(captor.capture());
        Incident created = captor.getValue();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTitle()).isEqualTo("Elevated error rate detected in order-service");
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(created.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(created.getRootService()).isEqualTo("order-service");
        assertThat(created.getErrorCount()).isEqualTo(5);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void criticalEventAnywhereInCurrentWindowMakesIncidentCritical() {
        when(counter.record(any())).thenReturn(new RedisFailureCounter.WindowSnapshot(5, true));

        service.correlate(event("order-service", EventSeverity.ERROR));

        verify(repository).save(argThat(incident -> incident.getSeverity() == IncidentSeverity.CRITICAL));
    }

    @ParameterizedTest
    @EnumSource(value = EventSeverity.class, names = {"WARNING", "INFO"})
    void nonFailureEventsDoNotTouchCountersOrIncidents(EventSeverity severity) {
        service.correlate(event("order-service", severity));

        verifyNoInteractions(counter, repository, jdbc);
    }

    @Test
    void differentServicesAreCountedSeparately() {
        var counts = new HashMap<String, Long>();
        when(counter.record(any())).thenAnswer(invocation -> {
            ServiceEvent event = invocation.getArgument(0);
            return new RedisFailureCounter.WindowSnapshot(counts.merge(event.serviceName(), 1L, Long::sum), false);
        });
        for (int i = 0; i < 4; i++) service.correlate(event("order-service", EventSeverity.ERROR));
        service.correlate(event("payment-service", EventSeverity.ERROR));
        verify(repository, never()).save(any());

        service.correlate(event("order-service", EventSeverity.ERROR));

        verify(repository).save(argThat(incident -> incident.getRootService().equals("order-service")
                && incident.getErrorCount() == 5));
    }

    @Test
    void activeMatchingIncidentSuppressesRepeatedCreation() {
        when(counter.record(any())).thenReturn(new RedisFailureCounter.WindowSnapshot(5, false),
                new RedisFailureCounter.WindowSnapshot(6, true));
        when(repository.existsByRootServiceAndStatusIn("order-service",
                List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING))).thenReturn(true);

        service.correlate(event("order-service", EventSeverity.ERROR));
        service.correlate(event("order-service", EventSeverity.CRITICAL));

        verify(repository, never()).save(any());
    }

    @Test
    void configuredThresholdAndObservedCountAreUsed() {
        service = new CorrelationService(counter, repository, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                new CorrelationProperties(3, 10));
        when(counter.record(any())).thenReturn(new RedisFailureCounter.WindowSnapshot(4, false));

        service.correlate(event("order-service", EventSeverity.ERROR));

        verify(repository).save(argThat(incident -> incident.getErrorCount() == 4));
    }

    @Test
    void redisFailurePropagatesForKafkaRetry() {
        when(counter.record(any())).thenThrow(new RedisConnectionFailureException("Unavailable"));

        assertThatThrownBy(() -> service.correlate(event("order-service", EventSeverity.ERROR)))
                .isInstanceOf(RedisConnectionFailureException.class);
        verifyNoInteractions(repository, jdbc);
    }

    private ServiceEvent event(String name, EventSeverity severity) {
        return new ServiceEvent(UUID.randomUUID(), name, EventType.REQUEST_FAILED, severity,
                "Request failed", NOW, null, Map.of());
    }
}
