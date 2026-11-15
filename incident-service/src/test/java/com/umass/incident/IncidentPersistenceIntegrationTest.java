package com.umass.incident;

import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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
        String url = System.getenv("TEST_DATABASE_URL");
        String username;
        String password;
        if (url == null || url.isBlank()) {
            postgres = new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("incidents")
                    .withUsername("test_" + UUID.randomUUID().toString().replace("-", ""))
                    .withPassword(UUID.randomUUID().toString());
            postgres.start();
            url = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        } else {
            username = requiredEnvironment("TEST_DATABASE_USERNAME");
            password = requiredEnvironment("TEST_DATABASE_PASSWORD");
        }
        application = new SpringApplicationBuilder(IncidentServiceApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password);
        repository = application.getBean(IncidentRepository.class);
        mapper = application.getBean(ObjectMapper.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for an external test database");
        }
        return value;
    }

    @AfterEach
    void cleanUpRows() {
        repository.deleteAllById(createdIds);
        createdIds.clear();
    }

    @AfterAll
    void stopApplication() throws Exception {
        if (client != null) {
            client.close();
        }
        if (application != null) {
            application.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void flywayMigrationRunsAgainstPostgresql() {
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void repositoryPersistsAndFiltersStatus() {
        UUID id = UUID.randomUUID();
        createdIds.add(id);
        repository.saveAndFlush(new Incident(id, "Repository test", IncidentSeverity.MEDIUM,
                "inventory-service", 3, Instant.parse("2026-11-15T12:00:00Z")));

        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findByStatus(IncidentStatus.OPEN)).extracting(Incident::getId).contains(id);
        assertThat(repository.findByStatus(IncidentStatus.RESOLVED)).extracting(Incident::getId).doesNotContain(id);
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
}
