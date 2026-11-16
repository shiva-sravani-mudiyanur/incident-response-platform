package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.exception.EventPublishException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaEventPublisher {
    private final KafkaTemplate<String, ServiceEvent> template;
    private final String topic;
    private final Duration publishTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, ServiceEvent> template,
                               @Value("${app.kafka.topic}") String topic,
                               @Value("${app.kafka.publish-timeout}") Duration publishTimeout) {
        this.template = template;
        this.topic = topic;
        this.publishTimeout = publishTimeout;
    }

    public void publish(ServiceEvent event) {
        try {
            template.send(topic, event.serviceName(), event)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw new EventPublishException(exception);
        }
    }
}
