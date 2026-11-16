package com.umass.event;

import com.umass.event.model.EventSeverity;
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

import static org.assertj.core.api.Assertions.assertThat;

// Start the real application directly to retain JUnit 5 with Spring Framework 7.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventApplicationIntegrationTest {
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private ObjectMapper mapper;
    private String baseUrl;

    @BeforeAll
    void startApplication() {
        application = new SpringApplicationBuilder(EventServiceApplication.class).run("--server.port=0");
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
            if (application != null) {
                application.close();
            }
        }
    }

    @Test
    void realHttpAcceptsEventWithoutDatabaseOrMessaging() throws Exception {
        var response = post("""
                {"serviceName":"payment-service","eventType":"DATABASE_ERROR","severity":"ERROR",
                 "message":"Database connection failed","timestamp":"2026-11-15T12:00:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(202);
        String id = mapper.readTree(response.body()).get("eventId").asString();
        assertThat(UUID.fromString(id)).isNotNull();
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

    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
