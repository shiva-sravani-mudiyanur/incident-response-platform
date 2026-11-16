package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.model.EventSeverity;
import com.umass.event.model.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.umass.event.exception.EventPublishException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {
    @Mock
    private KafkaTemplate<String, ServiceEvent> template;

    @Test
    void publishesEventWithServiceNameAsPartitionKeyAndWaitsForAcknowledgement() {
        ServiceEvent event = event();
        when(template.send("service-events", event.serviceName(), event))
                .thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1));

        publisher.publish(event);

        verify(template).send("service-events", event.serviceName(), event);
    }

    private ServiceEvent event() {
        return new ServiceEvent(UUID.randomUUID(), "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Connection failed", Instant.parse("2026-11-16T12:00:00Z"),
                "trace-123", Map.of("database", "payments"));
    }

    @Test
    void failedBrokerAcknowledgementIsReported() {
        ServiceEvent event = event();
        when(template.send("service-events", event.serviceName(), event))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        var publisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
    }

    @Test
    void synchronousSendFailureIsReported() {
        ServiceEvent event = event();
        when(template.send("service-events", event.serviceName(), event))
                .thenThrow(new IllegalStateException("Metadata unavailable"));
        var publisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
    }

    @Test
    void unconfirmedPublicationTimesOut() {
        ServiceEvent event = event();
        when(template.send("service-events", event.serviceName(), event)).thenReturn(new CompletableFuture<>());
        var publisher = new KafkaEventPublisher(template, "service-events", Duration.ofMillis(10));

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
    }

    @Test
    void interruptionIsReportedAndInterruptFlagRestored() {
        ServiceEvent event = event();
        when(template.send("service-events", event.serviceName(), event)).thenReturn(new CompletableFuture<>());
        var publisher = new KafkaEventPublisher(template, "service-events", Duration.ofSeconds(1));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
