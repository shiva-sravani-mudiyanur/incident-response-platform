package com.umass.simulator.service;

import com.umass.simulator.config.SimulatorProperties;
import com.umass.simulator.dto.ServiceEvent;
import com.umass.simulator.model.EventSeverity;
import com.umass.simulator.model.EventType;
import com.umass.simulator.model.Scenario;
import com.umass.simulator.exception.SimulationFailedException;
import org.springframework.web.client.RestClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    @Mock private EventServiceClient client;
    @Mock private SimulationDelay delay;
    private SimulationService service;

    @BeforeEach
    void setUp() {
        service = new SimulationService(client, Clock.fixed(NOW, ZoneOffset.UTC), properties(5), delay);
    }

    @Test
    void normalScenarioPublishesHealthyEventsForAllThreeServices() {
        var response = service.simulate(Scenario.NORMAL);

        var captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(client, times(3)).publish(captor.capture());
        var events = captor.getAllValues();
        assertThat(events).extracting(ServiceEvent::serviceName)
                .containsExactly("order-service", "payment-service", "inventory-service");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.severity()).isEqualTo(EventSeverity.INFO);
            assertThat(event.eventType()).isEqualTo(EventType.SERVICE_STARTED);
            assertThat(event.timestamp()).isEqualTo(NOW);
            assertThat(event.message()).isNotBlank();
            assertThat(event.traceId()).isEqualTo(response.traceId());
        });
        assertThat(events).extracting(ServiceEvent::eventId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(response.eventCount()).isEqualTo(3);
        assertThat(response.eventIds()).containsExactlyElementsOf(events.stream().map(ServiceEvent::eventId).toList());
        assertThat(response.scenario()).isEqualTo(Scenario.NORMAL);
        verifyNoInteractions(delay);
    }

    @ParameterizedTest
    @CsvSource({"DATABASE_FAILURE,order-service,DATABASE_ERROR", "PAYMENT_FAILURE,payment-service,TIMEOUT"})
    void failureScenarioPublishesRepeatedRealisticTimeoutEvents(Scenario scenario, String name, EventType type) {
        var response = service.simulate(scenario);

        var captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(client, times(5)).publish(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.serviceName()).isEqualTo(name);
            assertThat(event.eventType()).isEqualTo(type);
            assertThat(event.severity()).isEqualTo(EventSeverity.ERROR);
            assertThat(event.timestamp()).isEqualTo(NOW);
            assertThat(event.traceId()).isEqualTo(response.traceId());
            assertThat(event.message()).contains("timed out");
        });
        assertThat(response.eventIds()).hasSize(5).doesNotHaveDuplicates();
        assertThat(response.eventCount()).isEqualTo(5);
        verifyNoInteractions(delay);
    }

    @Test
    void failureVolumeIsConfigurableWithoutAnIncidentDetectionDependency() {
        service = new SimulationService(client, Clock.fixed(NOW, ZoneOffset.UTC), properties(3), delay);

        assertThat(service.simulate(Scenario.DATABASE_FAILURE).eventCount()).isEqualTo(3);

        verify(client, times(3)).publish(any());
    }

    @Test
    void cascadeSendsOrderedFailureWavesWithSharedTraceAndTimestampsAfterDelays() throws Exception {
        var now = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        doAnswer(invocation -> {
            Duration duration = invocation.getArgument(0);
            now.updateAndGet(time -> time.plus(duration));
            return null;
        }).when(delay).pause(any());
        service = new SimulationService(client, clock, properties(5), delay);

        var response = service.simulate(Scenario.CASCADE_FAILURE);

        var calls = inOrder(client, delay);
        calls.verify(client, times(5)).publish(argThat(e -> e.serviceName().equals("order-service")));
        calls.verify(delay).pause(Duration.ofMillis(250));
        calls.verify(client, times(5)).publish(argThat(e -> e.serviceName().equals("payment-service")));
        calls.verify(delay).pause(Duration.ofMillis(250));
        calls.verify(client, times(5)).publish(argThat(e -> e.serviceName().equals("inventory-service")));
        var captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(client, times(15)).publish(captor.capture());
        var events = captor.getAllValues();
        assertThat(events.subList(0, 5)).allSatisfy(event -> {
            assertThat(event.severity()).isEqualTo(EventSeverity.CRITICAL);
            assertThat(event.eventType()).isEqualTo(EventType.DATABASE_ERROR);
            assertThat(event.timestamp()).isEqualTo(NOW);
        });
        assertThat(events.subList(5, 10)).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(EventType.DEPENDENCY_ERROR);
            assertThat(event.timestamp()).isEqualTo(NOW.plusMillis(250));
        });
        assertThat(events.subList(10, 15)).allSatisfy(event ->
                assertThat(event.timestamp()).isEqualTo(NOW.plusMillis(500)));
        assertThat(events).allSatisfy(event -> assertThat(event.traceId()).isEqualTo(response.traceId()));
        assertThat(response.eventIds()).hasSize(15).doesNotHaveDuplicates();
    }

    @Test
    void downstreamFailureStopsScenarioAndReportsOnlyConfirmedEvents() {
        doNothing().doNothing().doThrow(new RestClientException("Unavailable")).when(client).publish(any());

        Throwable caught = catchThrowable(() -> service.simulate(Scenario.DATABASE_FAILURE));

        assertThat(caught).isInstanceOf(SimulationFailedException.class);
        var failure = (SimulationFailedException) caught;
        assertThat(failure.getScenario()).isEqualTo(Scenario.DATABASE_FAILURE);
        assertThat(failure.getTraceId()).isNotBlank();
        var captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(client, times(3)).publish(captor.capture());
        assertThat(failure.getAcceptedEventIds()).containsExactlyElementsOf(
                captor.getAllValues().subList(0, 2).stream().map(ServiceEvent::eventId).toList());
    }

    @Test
    void cascadeInterruptionPreservesInterruptFlagAndDoesNotPublishDownstreamWaves() throws Exception {
        doThrow(new InterruptedException("Cancelled")).when(delay).pause(any());
        try {
            Throwable caught = catchThrowable(() -> service.simulate(Scenario.CASCADE_FAILURE));

            assertThat(caught).isInstanceOf(SimulationFailedException.class);
            assertThat(((SimulationFailedException) caught).getAcceptedEventIds()).hasSize(5);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(client, times(5)).publish(argThat(event -> event.serviceName().equals("order-service")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void separateRunsHaveNewTraceAndEventIds() {
        var first = service.simulate(Scenario.NORMAL);
        var second = service.simulate(Scenario.NORMAL);

        assertThat(first.traceId()).isNotEqualTo(second.traceId());
        assertThat(first.eventIds()).doesNotContainAnyElementsOf(second.eventIds());
    }

    private SimulatorProperties properties(int count) {
        return new SimulatorProperties(URI.create("http://event-service.test"), count,
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(15));
    }
}
