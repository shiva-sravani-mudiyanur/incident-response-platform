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
        insert(event).thenReturn(1);

        service.process(event);

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
        ServiceEvent event = event();
        insert(event).thenThrow(new DataAccessResourceFailureException("PostgreSQL unavailable"));

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(DataAccessResourceFailureException.class);
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
    }

    @Test
    void onlyTheSuccessfulInsertCorrelatesWhenSameEventIsProcessedTwice() {
        ServiceEvent event = event();
        insert(event).thenReturn(1, 0);

        service.process(event);
        service.process(event);

        verify(correlation, times(1)).correlate(event);
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
