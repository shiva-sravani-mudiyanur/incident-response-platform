package com.umass.incident;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import com.umass.incident.service.EventProcessingService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Manual lifecycle deliberately avoids the Boot 4/JUnit 5.13 SpringExtension incompatibility.
// Every infrastructure address is supplied by these private Testcontainers, never Compose.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalEndpointsIntegrationTest {
    private static final String[] HEALTH_FIELDS = {
            "serviceName", "status", "errorsLastMinute", "activeIncidentCount", "lastEventTimestamp"};
    private PostgreSQLContainer postgres;
    private GenericContainer<?> redis;
    private KafkaContainer kafka;
    private ConfigurableApplicationContext application;
    private EventProcessingService processing;
    private ObjectMapper mapper;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll
    void startInfrastructureAndHttpServer() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("operational_endpoints")
                .withUsername("test_" + UUID.randomUUID().toString().replace("-", ""))
                .withPassword(UUID.randomUUID().toString());
        postgres.start();
        redis = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);
        redis.start();
        kafka = new KafkaContainer("apache/kafka:4.1.2");
        kafka.start();
        String topic = "operational-" + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        application = new SpringApplicationBuilder(IncidentServiceApplication.class).run(
                "--server.port=0",
                "--KAFKA_BOOTSTRAP_SERVERS=" + kafka.getBootstrapServers(),
                "--KAFKA_EVENTS_TOPIC=" + topic,
                "--KAFKA_CONSUMER_GROUP=operational-" + UUID.randomUUID(),
                "--REDIS_HOST=" + redis.getHost(), "--REDIS_PORT=" + redis.getMappedPort(6379),
                "--DB_HOST=" + postgres.getHost(), "--DB_PORT=" + postgres.getMappedPort(5432),
                "--DB_NAME=" + postgres.getDatabaseName(), "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword(),
                // Isolate health's five-error DOWN threshold from automatic HIGH incident creation.
                "--incident.correlation.threshold=100",
                // Native Windows JDK CPU-load calls can block a scrape; keep this test about business meters.
                "--management.metrics.enable.system.cpu=false", "--management.metrics.enable.process.cpu=false");
        processing = application.getBean(EventProcessingService.class);
        mapper = application.getBean(ObjectMapper.class);
        baseUrl = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void stopInfrastructure() {
        try {
            if (client != null) client.close();
        } finally {
            try {
                if (application != null) application.close();
            } finally {
                try {
                    if (kafka != null) kafka.stop();
                } finally {
                    try {
                        if (redis != null) redis.stop();
                    } finally {
                        if (postgres != null) postgres.stop();
                    }
                }
            }
        }
    }

    @Test
    void actuatorPublishesOnlyReadOnlyHealthInfoMetricsAndPrometheus() throws Exception {
        // Boot may advertise liveness/readiness group names without disclosing component details.
        assertThat(json(get("/actuator/health"))).containsEntry("status", "UP")
                .doesNotContainKeys("components", "details");
        assertThat(json(get("/actuator/info"))).isEmpty();
        var prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("incident_events_processed_total{application=\"incident-service\"}",
                "incident_events_duplicates_total{application=\"incident-service\"}",
                "incident_events_errors_total{application=\"incident-service\"}",
                "incident_incidents_total{application=\"incident-service\",source=\"manual\"}",
                "incident_incidents_total{application=\"incident-service\",source=\"correlation\"}",
                "incident_incidents_active{application=\"incident-service\"}");
        Map<String, Object> metrics = json(get("/actuator/metrics"));
        assertThat(metrics).containsOnlyKeys("names");
        assertThat(((List<?>) metrics.get("names")).contains("jvm.memory.used")).isTrue();
        assertThat(json(get("/actuator/metrics/jvm.memory.used")))
                .containsEntry("name", "jvm.memory.used").containsKeys("measurements", "availableTags");
        assertThat(application.getBean(WebEndpointsSupplier.class).getEndpoints())
                .extracting(endpoint -> endpoint.getEndpointId().toString())
                .containsExactlyInAnyOrder("health", "info", "metrics", "prometheus");
        var environment = application.getEnvironment();
        assertThat(environment.getProperty("management.endpoints.access.default")).isEqualTo("none");
        for (String endpoint : List.of("health", "info", "metrics", "prometheus")) {
            assertThat(environment.getProperty("management.endpoint." + endpoint + ".access")).isEqualTo("read-only");
            assertThat(request("POST", "/actuator/" + endpoint).statusCode()).isIn(404, 405);
        }
        assertThat(environment.getProperty("management.endpoints.jmx.exposure.exclude")).isEqualTo("*");
        assertThat(environment.getProperty("management.info.defaults.enabled", Boolean.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "mappings", "loggers", "heapdump",
            "threaddump", "shutdown", "conditions", "flyway", "scheduledtasks", "caches"})
    void sensitiveActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        assertThat(get("/actuator/" + endpoint).statusCode()).isIn(404, 405);
        if (endpoint.equals("shutdown") || endpoint.equals("loggers")) {
            assertThat(request("POST", "/actuator/" + endpoint).statusCode()).isIn(404, 405);
        }
    }

    @Test
    void actuatorDiscoveryDoesNotDiscloseEndpoints() throws Exception {
        assertThat(get("/actuator").statusCode()).isEqualTo(404);
    }

    @Test
    void infoEventRegistersHealthyServiceWithoutCountingAnError() throws Exception {
        String service = serviceName();
        ServiceEvent event = event(service, EventSeverity.INFO, Instant.now().truncatedTo(ChronoUnit.MICROS));
        processing.process(event);

        Map<String, Object> health = json(get("/api/services/" + service + "/health"));
        assertHealth(health, service, "HEALTHY", 0, 0);
        assertThat(Instant.parse((String) health.get("lastEventTimestamp"))).isEqualTo(event.timestamp());
    }

    @Test
    void twoErrorsDegradeAndFiveErrorsTakeServiceDownWithoutAnIncident() throws Exception {
        String service = serviceName();
        processing.process(event(service, EventSeverity.ERROR));
        assertHealth(json(get("/api/services/" + service + "/health")), service, "HEALTHY", 1, 0);
        processing.process(event(service, EventSeverity.CRITICAL));
        assertHealth(json(get("/api/services/" + service + "/health")), service, "DEGRADED", 2, 0);
        for (int i = 0; i < 3; i++) processing.process(event(service, EventSeverity.ERROR));
        assertHealth(json(get("/api/services/" + service + "/health")), service, "DOWN", 5, 0);
    }

    @Test
    void activeHighIncidentOverridesHealthyErrorCountAndResolvedDoesNot() throws Exception {
        String service = serviceName();
        processing.process(event(service, EventSeverity.INFO));
        var repository = application.getBean(IncidentRepository.class);
        var incident = new Incident(UUID.randomUUID(), "Operational test", IncidentSeverity.HIGH,
                service, 0, Instant.now());
        repository.save(incident);
        assertHealth(json(get("/api/services/" + service + "/health")), service, "DOWN", 0, 1);
        incident.updateStatus(IncidentStatus.RESOLVED, Instant.now());
        repository.save(incident);
        assertHealth(json(get("/api/services/" + service + "/health")), service, "HEALTHY", 0, 0);
    }

    @Test
    void servicesAreSortedHealthDtosAndDoNotMixServiceErrorCounts() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String first = "a-operational-" + suffix;
        String last = "z-operational-" + suffix;
        processing.process(event(last, EventSeverity.ERROR));
        processing.process(event(last, EventSeverity.ERROR));
        processing.process(event(first, EventSeverity.INFO));
        HttpResponse<String> response = get("/api/services");
        assertThat(response.statusCode()).isEqualTo(200);
        List<?> services = mapper.readValue(response.body(), List.class);
        var names = new ArrayList<String>();
        for (Object value : services) {
            Map<String, Object> health = object(value);
            assertThat(health).containsOnlyKeys(HEALTH_FIELDS);
            names.add((String) health.get("serviceName"));
            if (first.equals(health.get("serviceName"))) assertHealth(health, first, "HEALTHY", 0, 0);
            if (last.equals(health.get("serviceName"))) assertHealth(health, last, "DEGRADED", 2, 0);
        }
        assertThat(names).isSorted().doesNotHaveDuplicates().contains(first, last);
    }

    @Test
    void unknownServiceHealthAndHistoryReturnNotFound() throws Exception {
        String service = serviceName();
        assertThat(get("/api/services/" + service + "/health").statusCode()).isEqualTo(404);
        assertThat(get("/api/services/" + service + "/events?page=0&size=20").statusCode()).isEqualTo(404);
    }

    @Test
    void serviceHistoryIsScopedPaginatedAndContainsOnlyPublicEventDtoFields() throws Exception {
        String service = serviceName();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ServiceEvent oldest = event(service, EventSeverity.INFO, now.minusSeconds(3));
        ServiceEvent middle = event(service, EventSeverity.WARNING, now.minusSeconds(2));
        ServiceEvent newest = event(service, EventSeverity.ERROR, now.minusSeconds(1));
        for (ServiceEvent event : List.of(oldest, middle, newest)) processing.process(event);
        processing.process(event(serviceName(), EventSeverity.INFO));

        Map<String, Object> page = json(get("/api/services/" + service + "/events?page=0&size=2"));
        assertThat(page).containsOnlyKeys("serviceName", "events", "page", "size", "totalElements", "totalPages")
                .containsEntry("serviceName", service);
        assertNumber(page, "page", 0);
        assertNumber(page, "size", 2);
        assertNumber(page, "totalElements", 3);
        assertNumber(page, "totalPages", 2);
        List<?> events = (List<?>) page.get("events");
        assertThat(events).hasSize(2);
        assertEvent(object(events.get(0)), newest);
        assertEvent(object(events.get(1)), middle);
        Map<String, Object> second = json(get("/api/services/" + service + "/events?page=1&size=2"));
        assertNumber(second, "page", 1);
        assertThat((List<?>) second.get("events")).hasSize(1);
        assertEvent(object(((List<?>) second.get("events")).getFirst()), oldest);
        Map<String, Object> beyond = json(get("/api/services/" + service + "/events?page=2&size=2"));
        assertThat((List<?>) beyond.get("events")).isEmpty();
        Map<String, Object> defaults = json(get("/api/services/" + service + "/events"));
        assertNumber(defaults, "page", 0);
        assertNumber(defaults, "size", 20);
        assertThat((List<?>) defaults.get("events")).hasSize(3);
        assertThat(get("/api/services/" + service + "/events?page=0&size=1").statusCode()).isEqualTo(200);
        assertThat(get("/api/services/" + service + "/events?page=0&size=100").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1&size=20", "page=0&size=0", "page=0&size=-1", "page=0&size=101",
            "page=invalid&size=20", "page=0&size=invalid"})
    void historyRejectsInvalidPagination(String query) throws Exception {
        String service = serviceName();
        processing.process(event(service, EventSeverity.INFO));
        assertThat(get("/api/services/" + service + "/events?" + query).statusCode()).isEqualTo(400);
    }

    private void assertHealth(Map<String, Object> health, String service, String status, int errors, int active) {
        assertThat(health).containsOnlyKeys(HEALTH_FIELDS).containsEntry("serviceName", service).containsEntry("status", status);
        assertNumber(health, "errorsLastMinute", errors);
        assertNumber(health, "activeIncidentCount", active);
    }

    private void assertEvent(Map<String, Object> actual, ServiceEvent expected) {
        assertThat(actual).containsOnlyKeys("eventId", "serviceName", "eventType", "severity", "message",
                        "timestamp", "traceId", "processedAt")
                .containsEntry("eventId", expected.eventId().toString())
                .containsEntry("serviceName", expected.serviceName()).containsEntry("eventType", expected.eventType().name())
                .containsEntry("severity", expected.severity().name()).containsEntry("message", expected.message())
                .containsEntry("traceId", expected.traceId());
        assertThat(Instant.parse((String) actual.get("timestamp"))).isEqualTo(expected.timestamp());
        assertThat(Instant.parse((String) actual.get("processedAt"))).isNotNull();
    }

    private void assertNumber(Map<String, Object> value, String field, long expected) {
        assertThat(value.get(field)).isInstanceOf(Number.class);
        assertThat(((Number) value.get(field)).longValue()).isEqualTo(expected);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return object(mapper.readValue(response.body(), Map.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private String serviceName() {
        return "operational-" + UUID.randomUUID();
    }

    private ServiceEvent event(String service, EventSeverity severity) {
        return event(service, severity, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private ServiceEvent event(String service, EventSeverity severity, Instant timestamp) {
        return new ServiceEvent(UUID.randomUUID(), service, EventType.REQUEST_FAILED, severity,
                "Operational test event", timestamp, "trace-" + UUID.randomUUID(), Map.of("internal", "not-in-dto"));
    }
}
