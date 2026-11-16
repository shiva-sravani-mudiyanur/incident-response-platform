package com.umass.incident.service;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.exception.InvalidServiceEventException;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.model.IncidentEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EventProcessingServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static ValidatorFactory validators;
    @Mock
    private IncidentEventRepository repository;
    @Mock
    private CorrelationService correlation;
    private EventProcessingService service;

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
        service = new EventProcessingService(repository, validators.getValidator(), Clock.fixed(NOW, ZoneOffset.UTC), correlation);
    }

    @Test
    void validEventIsMappedAndPersistedWithProcessingTime() {
        ServiceEvent event = event();

        service.process(event);

        var captor = ArgumentCaptor.forClass(IncidentEvent.class);
        verify(repository).save(captor.capture());
        IncidentEvent stored = captor.getValue();
        assertThat(stored.getEventId()).isEqualTo(event.eventId());
        assertThat(stored.getServiceName()).isEqualTo(event.serviceName());
        assertThat(stored.getEventType()).isEqualTo(event.eventType());
        assertThat(stored.getSeverity()).isEqualTo(event.severity());
        assertThat(stored.getMessage()).isEqualTo(event.message());
        assertThat(stored.getTimestamp()).isEqualTo(event.timestamp());
        assertThat(stored.getTraceId()).isEqualTo(event.traceId());
        assertThat(stored.getProcessedAt()).isEqualTo(NOW);
        verify(correlation).correlate(event);
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void invalidEventIsRejectedBeforePersistence(ServiceEvent event) {
        assertThatThrownBy(() -> service.process(event)).isInstanceOf(InvalidServiceEventException.class);
        verifyNoInteractions(repository, correlation);
    }

    @Test
    void databaseFailurePropagatesSoKafkaCanRetry() {
        doThrow(new DataAccessResourceFailureException("PostgreSQL unavailable")).when(repository).save(any());

        assertThatThrownBy(() -> service.process(event())).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void alreadyPersistedEventIsNotCorrelatedAgainOnRedelivery() {
        ServiceEvent event = event();
        when(repository.existsById(event.eventId())).thenReturn(true);

        service.process(event);

        verify(repository, never()).save(any());
        verifyNoInteractions(correlation);
    }

    @Test
    void correlationFailurePropagatesToRollBackTheEventAndRetry() {
        doThrow(new IllegalStateException("Redis unavailable")).when(correlation).correlate(any());

        assertThatThrownBy(() -> service.process(event())).isInstanceOf(IllegalStateException.class);
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
