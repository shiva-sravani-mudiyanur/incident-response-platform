package com.umass.incident;

import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.model.IncidentEvent;
import com.umass.incident.repository.IncidentEventRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Real Kafka and PostgreSQL, with the production listener, validation and transaction boundary.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaEventPipelineIntegrationTest {
    private static final String TOPIC = "service-events";
    private KafkaContainer kafka;
    private PostgreSQLContainer postgres;
    private GenericContainer<?> redis;
    private Admin admin;
    private KafkaProducer<String, String> producer;
    private ConfigurableApplicationContext application;
    private IncidentEventRepository repository;
    private ObjectMapper mapper;

    @BeforeAll
    void startInfrastructureAndConsumer() throws Exception {
        kafka = new KafkaContainer("apache/kafka:4.1.2");
        kafka.start();
        postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("incident_events")
                .withUsername("test_" + UUID.randomUUID().toString().replace("-", ""))
                .withPassword(UUID.randomUUID().toString());
        postgres.start();
        redis = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);
        redis.start();
        admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        application = new SpringApplicationBuilder(IncidentServiceApplication.class).run(
                "--server.port=0",
                "--KAFKA_BOOTSTRAP_SERVERS=" + kafka.getBootstrapServers(),
                "--KAFKA_EVENTS_TOPIC=" + TOPIC,
                "--KAFKA_CONSUMER_GROUP=incident-processors",
                "--REDIS_HOST=" + redis.getHost(),
                "--REDIS_PORT=" + redis.getMappedPort(6379),
                "--incident.correlation.threshold=5",
                "--incident.correlation.window-seconds=60",
                "--DB_HOST=" + postgres.getHost(),
                "--DB_PORT=" + postgres.getMappedPort(5432),
                "--DB_NAME=" + postgres.getDatabaseName(),
                "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword());
        repository = application.getBean(IncidentEventRepository.class);

        mapper = application.getBean(ObjectMapper.class);
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
    }

    @AfterAll
    void stopInfrastructureAndConsumer() {
        try {
            if (producer != null) producer.close();
        } finally {
            try {
                if (application != null) application.close();
            } finally {
                try {
                    if (admin != null) admin.close();
                } finally {
                    try {
                        if (postgres != null) postgres.stop();
                    } finally {
                        try {
                            if (redis != null) redis.stop();
                        } finally {
                            if (kafka != null) kafka.stop();
                        }
                    }
                }
            }
        }
    }

    @Test
    void jsonEventIsConsumedAndPersistedInMigratedPostgresqlTable() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = payload(id);
        // An arbitrary producer class name must not control the consumer's target Java type.
        var record = new ProducerRecord<>(TOPIC, 0, "payment-service", mapper.writeValueAsString(payload));
        record.headers().add("__TypeId__", "untrusted.producer.UnavailableClass".getBytes(StandardCharsets.UTF_8));
        RecordMetadata sent = producer.send(record).get(20, TimeUnit.SECONDS);

        waitUntil(() -> repository.existsById(id));
        IncidentEvent stored = repository.findById(id).orElseThrow();
        assertThat(stored.getServiceName()).isEqualTo(payload.get("serviceName"));
        assertThat(stored.getEventType()).isEqualTo(EventType.DATABASE_ERROR);
        assertThat(stored.getSeverity()).isEqualTo(EventSeverity.ERROR);
        assertThat(stored.getMessage()).isEqualTo(payload.get("message"));
        assertThat(stored.getTimestamp()).isEqualTo(Instant.parse((String) payload.get("timestamp")));
        assertThat(stored.getTraceId()).isEqualTo(payload.get("traceId"));
        assertThat(stored.getProcessedAt()).isNotNull();
        assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true",
                Integer.class)).isEqualTo(1);
        waitForCommit(sent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-json", "missing-severity", "numeric-severity"})
    void invalidRecordIsNotStoredAndDoesNotBlockFollowingValidEvent(String invalidity) throws Exception {
        UUID invalidId = UUID.randomUUID();
        var invalid = payload(invalidId);
        if (invalidity.equals("missing-severity")) invalid.remove("severity");
        if (invalidity.equals("numeric-severity")) invalid.put("severity", 1);
        String json = invalidity.equals("malformed-json") ? "{not-json" : mapper.writeValueAsString(invalid);
        producer.send(new ProducerRecord<>(TOPIC, 0, "payment-service", json)).get(20, TimeUnit.SECONDS);
        UUID validId = UUID.randomUUID();
        RecordMetadata sent = publish(validId);

        waitForCommit(sent);

        assertThat(repository.existsById(invalidId)).isFalse();
        assertThat(repository.existsById(validId)).isTrue();
    }

    @Test
    void repeatedEventIdDoesNotCreateDuplicateRows() throws Exception {
        UUID id = UUID.randomUUID();
        waitForCommit(publish(id));

        waitForCommit(publish(id));

        assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from incident_events where event_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void fiveKafkaErrorsCreateOneIncidentAndSixthDoesNotDuplicateIt() throws Exception {
        String service = "kafka-correlation-" + UUID.randomUUID();
        var jdbc = application.getBean(JdbcTemplate.class);
        for (int i = 0; i < 5; i++) {
            var payload = payload(UUID.randomUUID());
            payload.put("serviceName", service);
            RecordMetadata sent = producer.send(new ProducerRecord<>(TOPIC, 0, service,
                    mapper.writeValueAsString(payload))).get(20, TimeUnit.SECONDS);
            waitForCommit(sent);
            if (i < 4) assertThat(jdbc.queryForObject("select count(*) from incidents where root_service = ?",
                    Integer.class, service)).isZero();
        }
        var extra = payload(UUID.randomUUID());
        extra.put("serviceName", service);
        waitForCommit(producer.send(new ProducerRecord<>(TOPIC, 0, service, mapper.writeValueAsString(extra)))
                .get(20, TimeUnit.SECONDS));

        assertThat(jdbc.queryForObject("select count(*) from incidents where root_service = ?",
                Integer.class, service)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select error_count from incidents where root_service = ?",
                Integer.class, service)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select status from incidents where root_service = ?",
                String.class, service)).isEqualTo("OPEN");
    }

    private RecordMetadata publish(UUID id) throws Exception {
        return producer.send(new ProducerRecord<>(TOPIC, 0, "payment-service", mapper.writeValueAsString(payload(id))))
                .get(20, TimeUnit.SECONDS);
    }

    private void waitForCommit(RecordMetadata sent) throws Exception {
        waitUntil(() -> {
            var offsets = admin.listConsumerGroupOffsets("incident-processors")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var committed = offsets.get(new TopicPartition(TOPIC, sent.partition()));
            return committed != null && committed.offset() > sent.offset();
        });
    }

    private void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka consumption/database persistence did not complete within 25 seconds");
    }

    private Map<String, Object> payload(UUID id) {
        var payload = new HashMap<String, Object>();
        payload.put("eventId", id.toString());
        payload.put("serviceName", "payment-service");
        payload.put("eventType", "DATABASE_ERROR");
        payload.put("severity", "ERROR");
        payload.put("message", "Database connection failed");
        payload.put("timestamp", "2026-11-16T12:00:00Z");
        payload.put("traceId", "trace-123");
        payload.put("metadata", Map.of("database", "payments"));
        return payload;
    }
}
