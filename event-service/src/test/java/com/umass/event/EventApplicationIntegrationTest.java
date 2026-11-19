package com.umass.event;

import com.umass.event.model.EventSeverity;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Start the real application directly to retain JUnit 5 with Spring Framework 7.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventApplicationIntegrationTest {
    private KafkaContainer kafka;
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private ObjectMapper mapper;
    private String baseUrl;

    @BeforeAll
    void startApplication() throws Exception {
        kafka = new KafkaContainer("apache/kafka:4.1.2");
        kafka.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("service-events", 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        application = new SpringApplicationBuilder(EventServiceApplication.class).run("--server.port=0",
                "--KAFKA_BOOTSTRAP_SERVERS=" + kafka.getBootstrapServers(),
                "--KAFKA_EVENTS_TOPIC=service-events");
        mapper = application.getBean(ObjectMapper.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    void stopApplication() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            try {
                if (application != null) {
                    application.close();
                }
            } finally {
                if (kafka != null) {
                    kafka.stop();
                }
            }
        }
    }

    @Test
    void realHttpPublishesJsonToKafkaWithoutJavaTypeHeaders() throws Exception {
        var response = post("""
                {"serviceName":"payment-service","eventType":"DATABASE_ERROR","severity":"ERROR",
                 "message":"Database connection failed","timestamp":"2026-11-15T12:00:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(202);
        String id = mapper.readTree(response.body()).get("eventId").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        try (var consumer = rawConsumer()) {
            consumer.subscribe(List.of("service-events"));
            ConsumerRecord<String, String> record = findEvent(consumer, id);
            var json = mapper.readTree(record.value());
            assertThat(record.key()).isEqualTo("payment-service");
            assertThat(json.get("eventId").asString()).isEqualTo(id);
            assertThat(json.get("severity").asString()).isEqualTo("ERROR");
            assertThat(json.get("timestamp").asString()).isEqualTo("2026-11-15T12:00:00Z");
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        }
    }

    @Test
    void suppliedEventIdIsReturnedUnchanged() throws Exception {
        UUID id = UUID.randomUUID();
        var payload = payload();
        payload.put("eventId", id.toString());

        var response = post(mapper.writeValueAsString(payload));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(mapper.readTree(response.body()).get("eventId").asString()).isEqualTo(id.toString());
    }

    @ParameterizedTest
    @EnumSource(EventSeverity.class)
    void acceptsEveryNamedSeverity(EventSeverity severity) throws Exception {
        var payload = payload();
        payload.put("severity", severity.name());

        assertThat(post(mapper.writeValueAsString(payload)).statusCode()).isEqualTo(202);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serviceName", "eventType", "severity", "message", "timestamp"})
    void realValidationRejectsMissingFields(String field) throws Exception {
        var payload = payload();
        payload.remove(field);

        var response = post(mapper.writeValueAsString(payload));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).get("errors").has(field)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"severity", "eventType"})
    void numericEnumOrdinalsAreRejected(String field) throws Exception {
        var payload = payload();
        payload.put(field, 1);

        assertThat(post(mapper.writeValueAsString(payload)).statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serviceName", "message", "traceId"})
    void realHttpRejectsEmbeddedNul(String field) throws Exception {
        var payload = payload();
        payload.put(field, "before\0after");

        var response = post(mapper.writeValueAsString(payload));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).get("errors").has(field)).isTrue();
    }

    @Test
    void basicHealthEndpointIsAvailable() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                .timeout(Duration.ofSeconds(10)).GET().build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
    }

    private Map<String, Object> payload() {
        var payload = new HashMap<String, Object>();
        payload.put("serviceName", "payment-service");
        payload.put("eventType", "DATABASE_ERROR");
        payload.put("severity", "ERROR");
        payload.put("message", "Database connection failed");
        payload.put("timestamp", "2026-11-15T12:00:00Z");
        return payload;
    }

    private KafkaConsumer<String, String> rawConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "json-contract-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> findEvent(KafkaConsumer<String, String> consumer, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (mapper.readTree(record.value()).get("eventId").asString().equals(id)) {
                    return record;
                }
            }
        }
        throw new AssertionError("Accepted event was not found in Kafka: " + id);
    }

    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
