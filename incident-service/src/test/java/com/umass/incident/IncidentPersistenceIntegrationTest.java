package com.umass.incident;

import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import com.umass.incident.service.IncidentService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// No SpringExtension: Spring Framework 7's extension requires JUnit 6, while this project uses JUnit 5.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentPersistenceIntegrationTest {
    private PostgreSQLContainer postgres;
    private ConfigurableApplicationContext application;
    private IncidentRepository repository;
    private ObjectMapper mapper;
    private HttpClient client;
    private String baseUrl;
    private final List<UUID> createdIds = new ArrayList<>();

    @BeforeAll
    void startApplication() {
        postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("incidents")
                .withUsername("test_" + UUID.randomUUID().toString().replace("-", ""))
                .withPassword(UUID.randomUUID().toString());
        postgres.start();
        application = new SpringApplicationBuilder(IncidentServiceApplication.class).run(
                "--server.port=0",
                "--DB_HOST=" + postgres.getHost(),
                "--DB_PORT=" + postgres.getMappedPort(5432),
                "--DB_NAME=" + postgres.getDatabaseName(),
                "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword());
        repository = application.getBean(IncidentRepository.class);
        mapper = application.getBean(ObjectMapper.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void cleanUpRows() {
        repository.deleteAllById(createdIds);
        createdIds.clear();
    }

    @AfterAll
    void stopApplication() throws Exception {
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
                if (postgres != null) {
                    postgres.stop();
                }
            }
        }
    }

    @Test
    void flywayMigrationRunsAgainstPostgresql() {
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select script from flyway_schema_history where version = '1' and success = true",
                String.class)).isEqualTo("V1__create_incidents_table.sql");
    }

    @Test
    void datasourceUsesDbSettingsAndValidatesSchema() {
        var environment = application.getEnvironment();
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                        + "/" + postgres.getDatabaseName());
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo(postgres.getUsername());
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void saveIncidentWritesPostgresqlRow() {
        Incident incident = newIncident();

        repository.saveAndFlush(incident);

        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select title from incidents where id = ?", String.class,
                incident.getId())).isEqualTo(incident.getTitle());
    }

    @Test
    void retrieveIncidentRestoresAllFields() {
        Incident incident = newIncident();
        repository.saveAndFlush(incident);

        Incident retrieved = repository.findById(incident.getId()).orElseThrow();

        assertThat(retrieved).usingRecursiveComparison().isEqualTo(incident);
    }

    @Test
    void filterIncidentsByStatus() {
        Incident open = newIncident();
        Incident resolved = newIncident();
        resolved.updateStatus(IncidentStatus.RESOLVED, resolved.getCreatedAt().plusSeconds(1));
        repository.saveAndFlush(open);
        repository.saveAndFlush(resolved);

        assertThat(repository.findByStatus(IncidentStatus.OPEN)).extracting(Incident::getId)
                .containsExactly(open.getId());
        assertThat(repository.findByStatus(IncidentStatus.RESOLVED)).extracting(Incident::getId)
                .containsExactly(resolved.getId());
        assertThat(repository.findByStatus(IncidentStatus.INVESTIGATING)).isEmpty();
    }

    @Test
    void updateStatusCommitsWithoutChangingOtherFields() {
        Incident incident = newIncident();
        repository.saveAndFlush(incident);

        var response = application.getBean(IncidentService.class)
                .updateStatus(incident.getId(), IncidentStatus.INVESTIGATING);

        Incident retrieved = repository.findById(incident.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(retrieved.getUpdatedAt()).isEqualTo(response.updatedAt()).isNotEqualTo(incident.getUpdatedAt());
        assertThat(retrieved).usingRecursiveComparison().ignoringFields("status", "updatedAt").isEqualTo(incident);
    }

    @Test
    void transactionCommitKeepsInsertedIncident() {
        Incident incident = newIncident();
        var transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> repository.saveAndFlush(incident));

        assertThat(repository.findById(incident.getId())).isPresent();
    }

    @Test
    void transactionRollbackDiscardsFlushedInsert() {
        Incident incident = newIncident();
        var transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> {
            repository.saveAndFlush(incident);
            status.setRollbackOnly();
        });

        assertThat(repository.findById(incident.getId())).isEmpty();
    }

    @Test
    void transactionFailureRollsBackFlushedStatusUpdate() {
        Incident incident = newIncident();
        repository.saveAndFlush(incident);
        var transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Incident managed = repository.findById(incident.getId()).orElseThrow();
            managed.updateStatus(IncidentStatus.RESOLVED, incident.getCreatedAt().plusSeconds(1));
            repository.flush();
            throw new IllegalStateException("Simulated downstream failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated downstream failure");

        Incident retrieved = repository.findById(incident.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(retrieved.getUpdatedAt()).isEqualTo(incident.getUpdatedAt());
    }

    @Test
    void httpLifecyclePersistsStatusUpdates() throws Exception {
        var created = send("POST", "/api/incidents", """
                {"title":"Integration failure","severity":"HIGH","rootService":"payment-service","errorCount":5}
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode original = mapper.readTree(created.body());
        UUID id = UUID.fromString(original.get("id").asString());
        createdIds.add(id);
        assertThat(created.headers().firstValue("Location")).contains("/api/incidents/" + id);
        assertThat(repository.findById(id)).isPresent();

        var retrieved = send("GET", "/api/incidents/" + id, null);
        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(retrieved.body())).isEqualTo(original);
        assertThat(send("GET", "/api/incidents", null).statusCode()).isEqualTo(200);
        JsonNode open = mapper.readTree(send("GET", "/api/incidents?status=OPEN", null).body());
        assertThat(open.findValuesAsString("id")).contains(id.toString());

        var updated = send("PATCH", "/api/incidents/" + id + "/status", "{\"status\":\"RESOLVED\"}");
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode result = mapper.readTree(updated.body());
        assertThat(result.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(result.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        JsonNode filtered = mapper.readTree(send("GET", "/api/incidents?status=OPEN", null).body());
        assertThat(filtered.findValuesAsString("id")).doesNotContain(id.toString());
    }

    @Test
    void realHttpReturnsControlledValidationAndNotFoundErrors() throws Exception {
        var invalid = send("POST", "/api/incidents", "{}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(invalid.body()).get("errors").has("title")).isTrue();
        var missing = send("GET", "/api/incidents/" + UUID.randomUUID(), null);
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method, publisher).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Incident newIncident() {
        UUID id = UUID.randomUUID();
        createdIds.add(id);
        return new Incident(id, "Repository test", IncidentSeverity.MEDIUM,
                "inventory-service", 3, Instant.parse("2026-11-15T12:00:00Z"));
    }
}
