package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.exception.EventPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private final KafkaTemplate<String, ServiceEvent> template;
    private final String topic;
    private final Duration publishTimeout;
    private final EventMetrics metrics;

    public KafkaEventPublisher(KafkaTemplate<String, ServiceEvent> template,
                               @Value("${app.kafka.topic}") String topic,
                               @Value("${app.kafka.publish-timeout}") Duration publishTimeout, EventMetrics metrics) {
        this.template = template;
        this.topic = topic;
        this.publishTimeout = publishTimeout;
        this.metrics = metrics;
    }

    public void publish(ServiceEvent event) {
        var sample = metrics.startPublish();
        try (var ignored = new EventTraceScope(event == null ? null : event.traceId())) {
            try {
                template.send(topic, event.serviceName(), event)
                        .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                metrics.failed(sample);
                logFailure(event);
                Thread.currentThread().interrupt();
                throw new EventPublishException(exception);
            } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                metrics.failed(sample);
                logFailure(event);
                throw new EventPublishException(exception);
            }
            metrics.published(sample);
            log.atInfo().addKeyValue("eventId", event.eventId())
                    .addKeyValue("serviceName", event.serviceName()).log("Published event");
        }
    }

    private void logFailure(ServiceEvent event) {
        // Never attach the exception: client/broker exceptions may contain payloads or credentials.
        log.atWarn().addKeyValue("eventId", event == null ? null : event.eventId())
                .addKeyValue("serviceName", event == null ? null : event.serviceName()).log("Event publication failed");
    }
}
