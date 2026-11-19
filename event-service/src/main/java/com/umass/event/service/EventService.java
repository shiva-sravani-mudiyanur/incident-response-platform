package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final KafkaEventPublisher publisher;

    private final EventMetrics metrics;

    public EventService(KafkaEventPublisher publisher, EventMetrics metrics) {
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public ServiceEvent accept(ServiceEvent event) {
        UUID eventId = event.eventId() == null ? UUID.randomUUID() : event.eventId();
        Map<String, String> metadata = event.metadata() == null ? Map.of() : Map.copyOf(event.metadata());
        ServiceEvent accepted = new ServiceEvent(eventId, event.serviceName(), event.eventType(),
                event.severity(), event.message(), event.timestamp(), event.traceId(), metadata);
        metrics.received();
        try (var ignored = new EventTraceScope(accepted.traceId())) {
            log.atInfo().addKeyValue("eventId", accepted.eventId())
                    .addKeyValue("serviceName", accepted.serviceName()).log("Received event");
            publisher.publish(accepted);
            return accepted;
        }
    }
}
