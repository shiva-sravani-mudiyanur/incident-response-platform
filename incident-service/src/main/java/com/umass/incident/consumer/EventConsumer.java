package com.umass.incident.consumer;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.service.EventProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private final EventProcessingService processing;

    public EventConsumer(EventProcessingService processing) {
        this.processing = processing;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void consume(ServiceEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        try (var trace = new com.umass.incident.observability.EventTraceScope(event == null ? null : event.traceId())) {
            processing.process(event);
            log.atInfo().addKeyValue("eventId", event == null ? null : event.eventId())
                    .addKeyValue("topic", topic).addKeyValue("partition", partition).addKeyValue("offset", offset)
                    .log("Event consumed");
        } catch (RuntimeException | Error failure) {
            try (var trace = new com.umass.incident.observability.EventTraceScope(event == null ? null : event.traceId())) {
                log.atWarn().addKeyValue("eventId", event == null ? null : event.eventId())
                        .addKeyValue("partition", partition).addKeyValue("offset", offset).log("Event consumption failed; delivery will retry");
            }
            throw failure;
        }
    }
}
