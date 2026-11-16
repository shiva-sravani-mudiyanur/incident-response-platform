package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.model.EventSeverity;
import com.umass.event.model.EventType;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import com.umass.event.exception.EventPublishException;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {
    @Mock
    private KafkaEventPublisher publisher;
    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(publisher);
    }

    @Test
    void publishesNormalizedEventBeforeReturningAcceptance() {
        var event = new ServiceEvent(null, "payment-service", EventType.REQUEST_FAILED,
                EventSeverity.ERROR, "Failure", Instant.parse("2026-11-16T12:00:00Z"), null, null);

        ServiceEvent accepted = service.accept(event);

        var captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue()).isSameAs(accepted);
        assertThat(captor.getValue().eventId()).isNotNull();
        assertThat(captor.getValue().metadata()).isEmpty();
    }

    @Test
    void publicationFailureDoesNotLogAcceptance() {
        doThrow(new EventPublishException(new IllegalStateException("Broker unavailable")))
                .when(publisher).publish(any());
        Logger logger = (Logger) LoggerFactory.getLogger(EventService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var event = new ServiceEvent(null, "payment-service", EventType.REQUEST_FAILED,
                    EventSeverity.ERROR, "Failure", Instant.parse("2026-11-16T12:00:00Z"), null, null);
            assertThatThrownBy(() -> service.accept(event)).isInstanceOf(EventPublishException.class);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void generatesEventIdWhenMissingAndPreservesPayload() {
        var event = new ServiceEvent(null, "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Database connection failed", Instant.parse("2026-11-15T12:00:00Z"),
                "trace-123", Map.of("database", "payments"));

        ServiceEvent accepted = service.accept(event);

        assertThat(accepted.eventId()).isNotNull();
        assertThat(accepted).usingRecursiveComparison().ignoringFields("eventId").isEqualTo(event);
    }

    @Test
    void preservesSuppliedEventId() {
        UUID id = UUID.randomUUID();
        var event = new ServiceEvent(id, "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Database connection failed", Instant.parse("2026-11-15T12:00:00Z"),
                "trace-123", Map.of());

        assertThat(service.accept(event).eventId()).isEqualTo(id);
    }

    @Test
    void acceptsMissingOptionalMetadataAndTraceId() {
        var event = new ServiceEvent(null, "payment-service", EventType.REQUEST_FAILED,
                EventSeverity.WARNING, "Request failed", Instant.parse("2026-11-15T12:00:00Z"), null, null);

        ServiceEvent accepted = service.accept(event);

        assertThat(accepted.metadata()).isEmpty();
        assertThat(accepted.traceId()).isNull();
    }

    @Test
    void acceptedMetadataIsAnImmutableSnapshot() {
        var metadata = new HashMap<>(Map.of("database", "payments"));
        var event = new ServiceEvent(null, "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Database connection failed", Instant.parse("2026-11-15T12:00:00Z"),
                "trace-123", metadata);

        ServiceEvent accepted = service.accept(event);
        metadata.put("database", "changed");

        assertThat(accepted.metadata()).containsEntry("database", "payments");
        assertThatThrownBy(() -> accepted.metadata().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void logsAcceptanceWithoutRawPayloadOrLogInjection() {
        Logger logger = (Logger) LoggerFactory.getLogger(EventService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var event = new ServiceEvent(null, "payment\r\nservice", EventType.DATABASE_ERROR,
                    EventSeverity.ERROR, "sensitive-message", Instant.parse("2026-11-15T12:00:00Z"),
                    "sensitive-trace", Map.of("secret", "sensitive-metadata"));

            ServiceEvent accepted = service.accept(event);

            assertThat(appender.list).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.INFO);
                assertThat(entry.getFormattedMessage()).contains("Accepted event", accepted.eventId().toString(),
                                "payment__service", "DATABASE_ERROR", "ERROR")
                        .doesNotContain("sensitive-message", "sensitive-metadata", "sensitive-trace", "\r", "\n");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
