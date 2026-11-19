package com.umass.incident.consumer;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.service.EventProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventConsumerTest {
    @Test
    void scopesTraceDuringProcessingAndRestoresItOnSuccessFailureAndNullTrace() {
        var original = event();
        for (String trace : new String[]{"trace-consumer", null}) {
            var input = new ServiceEvent(original.eventId(), original.serviceName(), original.eventType(),
                    original.severity(), original.message(), original.timestamp(), trace, original.metadata());
            for (boolean fail : new boolean[]{false, true}) {
                org.slf4j.MDC.put("traceId", "outer");
                org.mockito.Mockito.doAnswer(call -> {
                    org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo(trace);
                    if (fail) throw new IllegalStateException("not logged");
                    return null;
                }).when(processing).process(input);
                try {
                    if (fail) assertThatThrownBy(() -> new EventConsumer(processing).consume(input, "service-events", 0, 1))
                            .isInstanceOf(IllegalStateException.class);
                    else new EventConsumer(processing).consume(input, "service-events", 0, 1);
                    org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("outer");
                } finally { org.slf4j.MDC.clear(); }
            }
        }
    }

    @Mock
    private EventProcessingService processing;

    @Test
    void delegatesDeserializedEventToBusinessService() {
        ServiceEvent event = event();

        new EventConsumer(processing).consume(event, "service-events", 0, 12L);

        verify(processing).process(event);
    }

    @Test
    void processingFailurePropagatesForRetryRatherThanAcknowledgement() {
        ServiceEvent event = event();
        doThrow(new DataAccessResourceFailureException("Unavailable")).when(processing).process(event);

        assertThatThrownBy(() -> new EventConsumer(processing).consume(event, "service-events", 0, 12L))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private ServiceEvent event() {
        return new ServiceEvent(UUID.randomUUID(), "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Connection failed", Instant.parse("2026-11-16T12:00:00Z"), null, Map.of());
    }
}
