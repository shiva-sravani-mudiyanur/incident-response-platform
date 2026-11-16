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
