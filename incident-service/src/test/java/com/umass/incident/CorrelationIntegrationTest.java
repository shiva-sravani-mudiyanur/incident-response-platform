package com.umass.incident;

import com.umass.incident.config.CorrelationProperties;
import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentEventRepository;
import com.umass.incident.repository.IncidentRepository;
import com.umass.incident.service.EventProcessingService;
import com.umass.incident.service.IncidentService;
import com.umass.incident.service.RedisFailureCounter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelationIntegrationTest {
    private PostgreSQLContainer postgres;
    private GenericContainer<?> redis;
    private ConfigurableApplicationContext application;
    private EventProcessingService processing;
    private IncidentRepository incidents;
    private StringRedisTemplate template;

    @BeforeAll
    void startInfrastructure() {
        postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("correlation")
                .withUsername("test_" + UUID.randomUUID().toString().replace("-", ""))
                .withPassword(UUID.randomUUID().toString());
        postgres.start();
        redis = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);
        redis.start();
        application = new SpringApplicationBuilder(IncidentServiceApplication.class).run(
                "--server.port=0", "--KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:1",
                "--spring.kafka.listener.auto-startup=false",
                "--REDIS_HOST=" + redis.getHost(), "--REDIS_PORT=" + redis.getMappedPort(6379),
                "--DB_HOST=" + postgres.getHost(), "--DB_PORT=" + postgres.getMappedPort(5432),
                "--DB_NAME=" + postgres.getDatabaseName(), "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword(),
                "--incident.correlation.threshold=5", "--incident.correlation.window-seconds=60");
        processing = application.getBean(EventProcessingService.class);
        incidents = application.getBean(IncidentRepository.class);
        template = application.getBean(StringRedisTemplate.class);
    }

    @AfterAll
    void stopInfrastructure() {
        try {
            if (application != null) application.close();
        } finally {
            try {
                if (redis != null) redis.stop();
            } finally {
                if (postgres != null) postgres.stop();
            }
        }
    }

    @Test
    void fifthErrorCreatesOneIncidentAndSubsequentErrorsAreSuppressed() {
        String service = serviceName();
        processing.process(event(service, EventSeverity.ERROR));
        assertThat(forService(service)).isEmpty();
        for (int i = 0; i < 3; i++) processing.process(event(service, EventSeverity.ERROR));
        assertThat(forService(service)).isEmpty();

        processing.process(event(service, EventSeverity.ERROR));
        processing.process(event(service, EventSeverity.ERROR));

        assertThat(forService(service)).singleElement().satisfies(incident -> {
            assertThat(incident.getTitle()).isEqualTo("Elevated error rate detected in " + service);
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
            assertThat(incident.getErrorCount()).isEqualTo(5);
        });
        assertThat(application.getBean(CorrelationProperties.class)).isEqualTo(new CorrelationProperties(5, 60));
    }

    @Test
    void criticalAndErrorEventsCombineAndCreateCriticalIncident() {
        String service = serviceName();
        processing.process(event(service, EventSeverity.CRITICAL));
        for (int i = 0; i < 4; i++) processing.process(event(service, EventSeverity.ERROR));

        assertThat(forService(service)).singleElement()
                .satisfies(incident -> assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL));
    }

    @Test
    void warningsDoNotCountAndDifferentServicesDoNotMix() {
        String service = serviceName();
        String other = serviceName();
        for (int i = 0; i < 6; i++) processing.process(event(service, EventSeverity.WARNING));
        assertThat(template.hasKey("errors:{" + service + "}")).isFalse();
        for (int i = 0; i < 4; i++) processing.process(event(service, EventSeverity.ERROR));
        processing.process(event(other, EventSeverity.ERROR));

        assertThat(forService(service)).isEmpty();
        assertThat(forService(other)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = IncidentStatus.class, names = {"OPEN", "INVESTIGATING"})
    void existingActiveIncidentForServiceSuppressesAutomaticCreation(IncidentStatus status) {
        String service = serviceName();
        Incident existing = new Incident(UUID.randomUUID(), "Existing active incident", IncidentSeverity.HIGH,
                service, 1, Instant.now());
        existing.updateStatus(status, Instant.now());
        incidents.save(existing);
        for (int i = 0; i < 6; i++) processing.process(event(service, EventSeverity.ERROR));

        assertThat(forService(service)).hasSize(1);
    }

    @Test
    void resolvedIncidentDoesNotBlockANewIncidentWhenThresholdStillHolds() {
        String service = serviceName();
        for (int i = 0; i < 5; i++) processing.process(event(service, EventSeverity.ERROR));
        application.getBean(IncidentService.class).updateStatus(forService(service).getFirst().getId(), IncidentStatus.RESOLVED);

        processing.process(event(service, EventSeverity.ERROR));

        assertThat(forService(service)).hasSize(2);
        assertThat(forService(service).stream().filter(i -> i.getStatus() == IncidentStatus.OPEN)).hasSize(1);
    }

    @Test
    void concurrentThresholdCrossingsCreateOnlyOneActiveIncident() throws Exception {
        String service = serviceName();
        for (int i = 0; i < 4; i++) application.getBean(RedisFailureCounter.class).record(event(service, EventSeverity.ERROR));
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> processing.process(event(service, EventSeverity.ERROR))));
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }

        assertThat(forService(service)).hasSize(1);
    }

    @Test
    void persistedRedeliveryDoesNotCountTowardThresholdTwice() {
        String service = serviceName();
        ServiceEvent event = event(service, EventSeverity.ERROR);
        processing.process(event);
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(1);

        processing.process(event);

        assertThat(forService(service)).isEmpty();
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(1);
        assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from incident_events where event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
    }

    @Test
    void redisFailureRollsBackEventPersistenceAndSameEventCanBeRetried() {
        String service = serviceName();
        ServiceEvent event = event(service, EventSeverity.ERROR);
        String key = "errors:{" + service + "}";
        template.opsForValue().set(key, "invalid-data-type");
        try {
            assertThatThrownBy(() -> processing.process(event)).isInstanceOf(DataAccessException.class);
            assertThat(application.getBean(IncidentEventRepository.class).existsById(event.eventId())).isFalse();
        } finally {
            template.delete(key);
        }

        processing.process(event);

        assertThat(application.getBean(IncidentEventRepository.class).existsById(event.eventId())).isTrue();
    }

    @Test
    void concurrentDeliveriesOfSameEventAllSucceedWithoutDuplicateEffects() throws Exception {
        String service = serviceName();
        ServiceEvent event = event(service, EventSeverity.ERROR);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent deliveries did not start");
                processing.process(event);
                return null;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }

        assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from incident_events where event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(1);
        assertThat(forService(service)).isEmpty();
    }

    @Test
    void duplicateThresholdEventDoesNotReopenAResolvedIncident() {
        String service = serviceName();
        for (int i = 0; i < 4; i++) processing.process(event(service, EventSeverity.ERROR));
        ServiceEvent fifth = event(service, EventSeverity.ERROR);
        processing.process(fifth);
        UUID incidentId = forService(service).getFirst().getId();
        application.getBean(IncidentService.class).updateStatus(incidentId, IncidentStatus.RESOLVED);

        processing.process(fifth);

        assertThat(forService(service)).singleElement().satisfies(incident -> {
            assertThat(incident.getId()).isEqualTo(incidentId);
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getErrorCount()).isEqualTo(5);
        });
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(5);
    }

    @Test
    void committedDuplicateDoesNotRecreateAnExpiredRedisCounter() {
        String service = serviceName();
        ServiceEvent event = event(service, EventSeverity.ERROR);
        processing.process(event);
        String key = "errors:{" + service + "}";
        template.delete(key);

        processing.process(event);

        assertThat(template.hasKey(key)).isFalse();
    }

    @Test
    void reusedEventIdWithChangedPayloadPreservesOriginalRowAndCounters() {
        String service = serviceName();
        ServiceEvent original = event(service, EventSeverity.ERROR);
        processing.process(original);
        var repository = application.getBean(IncidentEventRepository.class);
        Instant processedAt = repository.findById(original.eventId()).orElseThrow().getProcessedAt();
        String other = serviceName();
        var changed = new ServiceEvent(original.eventId(), other, original.eventType(), EventSeverity.CRITICAL,
                "Different payload", original.timestamp().plusSeconds(1), "different-trace", Map.of());

        processing.process(changed);

        var stored = repository.findById(original.eventId()).orElseThrow();
        assertThat(stored.getServiceName()).isEqualTo(service);
        assertThat(stored.getSeverity()).isEqualTo(EventSeverity.ERROR);
        assertThat(stored.getMessage()).isEqualTo(original.message());
        assertThat(stored.getProcessedAt()).isEqualTo(processedAt);
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(1);
        assertThat(template.hasKey("errors:{" + other + "}")).isFalse();
    }

    @Test
    void postgresPrimaryKeyRejectsDuplicateEventIdEvenOutsideProcessor() {
        ServiceEvent event = event(serviceName(), EventSeverity.ERROR);
        processing.process(event);
        var jdbc = application.getBean(JdbcTemplate.class);

        assertThatThrownBy(() -> jdbc.update(
                "insert into incident_events select * from incident_events where event_id = ?", event.eventId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from incident_events where event_id = ?",
                Integer.class, event.eventId())).isEqualTo(1);
    }

    @Test
    void rollbackAfterRedisUpdateCanRetryWithoutDoubleCountingOrDuplicatingIncident() {
        String service = serviceName();
        for (int i = 0; i < 4; i++) processing.process(event(service, EventSeverity.ERROR));
        ServiceEvent fifth = event(service, EventSeverity.ERROR);
        var transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
        transaction.execute(status -> {
            processing.process(fifth);
            status.setRollbackOnly();
            return null;
        });
        assertThat(application.getBean(IncidentEventRepository.class).existsById(fifth.eventId())).isFalse();
        assertThat(forService(service)).isEmpty();
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(5);

        processing.process(fifth);

        assertThat(application.getBean(IncidentEventRepository.class).existsById(fifth.eventId())).isTrue();
        assertThat(template.opsForZSet().zCard("errors:{" + service + "}")).isEqualTo(5);
        assertThat(forService(service)).singleElement()
                .satisfies(incident -> assertThat(incident.getErrorCount()).isEqualTo(5));
    }

    private List<Incident> forService(String name) {
        return incidents.findAll().stream().filter(incident -> incident.getRootService().equals(name)).toList();
    }

    private String serviceName() {
        return "correlation-" + UUID.randomUUID();
    }

    private ServiceEvent event(String name, EventSeverity severity) {
        return new ServiceEvent(UUID.randomUUID(), name, EventType.REQUEST_FAILED, severity,
                "Request failed", Instant.parse("2026-11-16T12:00:00Z"), null, Map.of());
    }
}
