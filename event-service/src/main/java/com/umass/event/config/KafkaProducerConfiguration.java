package com.umass.event.config;

import com.umass.event.dto.ServiceEvent;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfiguration {
    @Bean
    ProducerFactory<String, ServiceEvent> eventProducerFactory(KafkaProperties properties, JsonMapper mapper) {
        // Reuse Boot's JSON/Instant configuration; never send Java class-name headers.
        var serializer = new JacksonJsonSerializer<ServiceEvent>(mapper).noTypeInfo();
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(),
                new StringSerializer(), serializer);
    }

    @Bean
    KafkaTemplate<String, ServiceEvent> eventKafkaTemplate(ProducerFactory<String, ServiceEvent> factory) {
        return new KafkaTemplate<>(factory);
    }
}
