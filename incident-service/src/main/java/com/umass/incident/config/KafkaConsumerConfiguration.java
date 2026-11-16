package com.umass.incident.config;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.exception.InvalidServiceEventException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);

    @Bean
    ConsumerFactory<String, ServiceEvent> eventConsumerFactory(KafkaProperties properties, JsonMapper mapper) {
        // Target our own contract explicitly; never trust a producer's Java type headers.
        var json = new JacksonJsonDeserializer<>(ServiceEvent.class, mapper, false);
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(),
                new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ServiceEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, ServiceEvent> consumerFactory, KafkaProperties properties,
            DefaultErrorHandler eventErrorHandler) {
        // Wire the typed factory explicitly: Boot's Object/Object lookup can otherwise
        // fall back to its default String consumer instead of this ServiceEvent factory.
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ServiceEvent>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(eventErrorHandler);
        factory.setAutoStartup(properties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(properties.getListener().getAckMode());
        factory.getContainerProperties().setMissingTopicsFatal(properties.getListener().isMissingTopicsFatal());
        return factory;
    }

    @Bean
    DefaultErrorHandler eventErrorHandler(@Value("${app.kafka.retry-interval}") Duration retryInterval) {
        var handler = new DefaultErrorHandler((record, exception) ->
                log.warn("Rejected Kafka record topic={} partition={} offset={} failureType={}",
                        record.topic(), record.partition(), record.offset(), exception.getClass().getSimpleName()),
                new FixedBackOff(retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        // Invalid data is logged and skipped. Database/processing failures keep retrying;
        // do not acknowledge a valid event whose database write has failed.
        handler.addNotRetryableExceptions(InvalidServiceEventException.class, DeserializationException.class);
        return handler;
    }
}
