package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.exception.EventPublishException;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventMetricsTest {
    @Test
    void failingCountersDoNotPreventAcceptanceOrReplacePublishFailure() {
        var meters = spy(new SimpleMeterRegistry());
        var broken = mock(io.micrometer.core.instrument.Counter.class);
        doThrow(new IllegalStateException("telemetry unavailable")).when(broken).increment();
        doReturn(broken).when(meters).counter("event.received");
        doReturn(broken).when(meters).counter("event.published");
        doReturn(broken).when(meters).counter("event.publish.errors");
        var isolatedMetrics = new EventMetrics(meters);
        var isolatedPublisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1), isolatedMetrics);
        var service = new EventService(isolatedPublisher, isolatedMetrics);
        var event = EventTraceLoggingTest.event("trace");
        when(template.send(eq("service-events"), eq(event.serviceName()), any(ServiceEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        var accepted = service.accept(event);
        assertThat(accepted.eventId()).isNotNull();
        assertThat(accepted.traceId()).isEqualTo(event.traceId());
        var original = new IllegalStateException("broker unavailable");
        when(template.send(eq("service-events"), eq(event.serviceName()), any(ServiceEvent.class))).thenThrow(original);
        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(EventPublishException.class).hasCause(original);
    }

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final EventMetrics metrics = new EventMetrics(registry);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ServiceEvent> template = mock(KafkaTemplate.class);
    private final KafkaEventPublisher publisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1), metrics);

    @Test
    void countsEachValidAcceptanceAttemptBeforePublicationEvenOnFailure() {
        var mockPublisher = mock(KafkaEventPublisher.class);
        var service = new EventService(mockPublisher, metrics);
        service.accept(EventTraceLoggingTest.event("trace"));
        assertThat(registry.get("event.received").counter().count()).isEqualTo(1);
        doThrow(new EventPublishException(new IllegalStateException("failure"))).when(mockPublisher).publish(any());
        assertThatThrownBy(() -> service.accept(EventTraceLoggingTest.event(null))).isInstanceOf(EventPublishException.class);
        assertThat(registry.get("event.received").counter().count()).isEqualTo(2);
        assertThat(registry.get("event.published").counter().count()).isZero();
    }

    @Test
    void confirmedAckRecordsOneSuccessDurationAndScopesTraceForDirectCalls() {
        var event = EventTraceLoggingTest.event("human\ntrace");
        MDC.put("traceId", "caller");
        try {
            when(template.send("service-events", event.serviceName(), event)).thenAnswer(invocation -> {
                assertThat(MDC.get("traceId")).isEqualTo(event.traceId());
                assertThat(registry.get("event.published").counter().count()).isZero();
                clock.add(Duration.ofMillis(25));
                return CompletableFuture.completedFuture(null);
            });
            publisher.publish(event);
            assertThat(MDC.get("traceId")).isEqualTo("caller");
            assertThat(registry.get("event.published").counter().count()).isEqualTo(1);
            assertThat(registry.get("event.publish.errors").counter().count()).isZero();
            var timer = registry.get("event.publish.duration").tag("outcome", "success").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25);
        } finally {
            MDC.clear();
        }
    }

    @Test
    void nullDirectPublishPreservesOriginalFailureContractAndRestoresCallerContext() {
        MDC.put("traceId", "caller");
        try {
            assertThatThrownBy(() -> publisher.publish(null)).isInstanceOf(EventPublishException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
            assertThat(MDC.get("traceId")).isEqualTo("caller");
            assertThat(registry.get("event.publish.errors").counter().count()).isEqualTo(1);
            assertThat(registry.get("event.publish.duration").tag("outcome", "error").timer().count()).isEqualTo(1);
            verifyNoInteractions(template);
        } finally {
            MDC.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "async", "timeout", "interrupted", "cancelled"})
    void eachPublishFailureCountsOnceAndRecordsErrorDurationWithoutRetry(String failure) throws Exception {
        var event = EventTraceLoggingTest.event("failure-trace");
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, ServiceEvent>> future = mock(CompletableFuture.class);
        when(template.send("service-events", event.serviceName(), event)).thenAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo(event.traceId());
            if (failure.equals("sync")) {
                clock.add(Duration.ofMillis(40));
                throw new IllegalStateException("secret-error");
            }
            return future;
        });
        if (!failure.equals("sync")) {
            when(future.get(1000, TimeUnit.MILLISECONDS)).thenAnswer(invocation -> {
                clock.add(Duration.ofMillis(40));
                throw switch (failure) {
                    case "async" -> new ExecutionException(new IllegalStateException("secret-error"));
                    case "timeout" -> new TimeoutException("secret-error");
                    case "interrupted" -> new InterruptedException("secret-error");
                    default -> new CancellationException("secret-error");
                };
            });
        }
        MDC.put("traceId", "caller");
        try {
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
            assertThat(MDC.get("traceId")).isEqualTo("caller");
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(failure.equals("interrupted"));
            assertThat(registry.get("event.published").counter().count()).isZero();
            assertThat(registry.get("event.publish.errors").counter().count()).isEqualTo(1);
            var timer = registry.get("event.publish.duration").tag("outcome", "error").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
            assertThat(registry.get("event.publish.duration").tag("outcome", "success").timer().count()).isZero();
            verify(template).send("service-events", event.serviceName(), event);
            verifyNoMoreInteractions(template);
        } finally {
            Thread.interrupted();
            MDC.clear();
        }
    }
}
