package com.umass.incident.service;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.exception.InvalidServiceEventException;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.repository.IncidentEventRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventProcessingServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static ValidatorFactory validators;
    @Mock
    private IncidentEventRepository repository;
    @Mock
    private CorrelationService correlation;
    private EventProcessingService service;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registry;
    private com.umass.incident.observability.IncidentMetrics metrics;

    @BeforeAll
    static void startValidation() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void stopValidation() {
        validators.close();
    }

    @BeforeEach
    void setUp() {
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        metrics = new com.umass.incident.observability.IncidentMetrics(registry, org.mockito.Mockito.mock(com.umass.incident.repository.IncidentRepository.class));
        service = new EventProcessingService(repository, validators.getValidator(), Clock.fixed(NOW, ZoneOffset.UTC), correlation, metrics);
    }

    @Test
    void directProcessingScopesTraceAndRestoresCallerContext() {
        var input = event();
        insert(input).thenReturn(1);
        org.mockito.Mockito.doAnswer(call -> {
            org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo(input.traceId());
            throw new IllegalStateException("not logged");
        }).when(correlation).correlate(input);
        org.slf4j.MDC.put("traceId", "caller");
        try {
            assertThatThrownBy(() -> service.process(input)).isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("caller");
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test
    void validEventIsMappedAndPersistedWithProcessingTime() {
        ServiceEvent event = event();
        insert(event).thenReturn(1);

        service.process(event);

        verify(correlation).correlate(event);
        // An unproxied unit call cannot claim a database commit. The transaction-interceptor test covers success.
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.processed").counter().count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void invalidEventIsRejectedBeforePersistence(ServiceEvent event) {
        assertThatThrownBy(() -> service.process(event)).isInstanceOf(InvalidServiceEventException.class);
        verifyNoInteractions(repository, correlation);
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void databaseFailurePropagatesSoKafkaCanRetry() {
        ServiceEvent event = event();
        insert(event).thenThrow(new DataAccessResourceFailureException("PostgreSQL unavailable"));

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(DataAccessResourceFailureException.class);
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
        verifyNoInteractions(correlation);
    }

    @Test
    void alreadyPersistedEventIsNotCorrelatedAgainOnRedelivery() {
        ServiceEvent event = event();
        insert(event).thenReturn(0);

        service.process(event);

        verifyNoInteractions(correlation);
    }

    @Test
    void correlationFailurePropagatesToRollBackTheEventAndRetry() {
        ServiceEvent event = event();
        insert(event).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(correlation).correlate(any());

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void onlyTheSuccessfulInsertCorrelatesWhenSameEventIsProcessedTwice() {
        ServiceEvent event = event();
        insert(event).thenReturn(1, 0);

        service.process(event);
        service.process(event);

        verify(correlation, times(1)).correlate(event);
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.duplicates").counter().count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(registry.get("incident.events.errors").counter().count()).isZero();
    }

    private OngoingStubbing<Integer> insert(ServiceEvent event) {
        return when(repository.insertIfAbsent(event.eventId(), event.serviceName(), event.eventType().name(),
                event.severity().name(), event.message(), event.timestamp().truncatedTo(ChronoUnit.MICROS),
                event.traceId(), NOW));
    }

    private static Stream<ServiceEvent> invalidEvents() {
        ServiceEvent event = event();
        return Stream.of(null,
                new ServiceEvent(null, event.serviceName(), event.eventType(), event.severity(), event.message(), NOW, null, null),
                new ServiceEvent(event.eventId(), " ", event.eventType(), event.severity(), event.message(), NOW, null, null),
                new ServiceEvent(event.eventId(), event.serviceName(), null, event.severity(), event.message(), NOW, null, null),
                new ServiceEvent(event.eventId(), event.serviceName(), event.eventType(), null, event.message(), NOW, null, null),
                new ServiceEvent(event.eventId(), event.serviceName(), event.eventType(), event.severity(), "", NOW, null, null),
                new ServiceEvent(event.eventId(), event.serviceName(), event.eventType(), event.severity(), event.message(), null, null, null));
    }

    private static ServiceEvent event() {
        return new ServiceEvent(UUID.randomUUID(), "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Connection failed", NOW.minusSeconds(10), "trace-123", Map.of());
    }
}
