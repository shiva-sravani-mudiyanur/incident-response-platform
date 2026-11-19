package com.umass.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

// Actual simulator HTTP server -> actual RestClient -> local event-service contract stub.
// No SpringExtension is used, preserving JUnit 5 compatibility with Spring Framework 7.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatorApplicationIntegrationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CopyOnWriteArrayList<JsonNode> submitted = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private String baseUrl;
    private volatile int failAfter = -1;

    @BeforeAll
    void startApplication() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/events", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/api/events")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                JsonNode event = mapper.readTree(exchange.getRequestBody().readAllBytes());
                submitted.add(event);
                boolean failed = failAfter >= 0 && submitted.size() > failAfter;
                String response = failed ? "{\"detail\":\"Test upstream unavailable\"}"
                        : "{\"eventId\":\"" + event.get("eventId").asString() + "\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failed ? 503 : 202, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        upstream.start();
        application = new SpringApplicationBuilder(ServiceSimulatorApplication.class).run(
                "--server.port=0",
                "--EVENT_SERVICE_BASE_URL=http://127.0.0.1:" + upstream.getAddress().getPort(),
                "--SIMULATOR_FAILURE_EVENT_COUNT=5", "--SIMULATOR_CASCADE_DELAY=20ms",
                "--SIMULATOR_CONNECT_TIMEOUT=1s", "--SIMULATOR_READ_TIMEOUT=2s");
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @BeforeEach
    void resetStub() {
        submitted.clear();
        failAfter = -1;
    }

    @AfterAll
    void stopApplication() {
        try {
            if (client != null) client.close();
        } finally {
            try {
                if (application != null) application.close();
            } finally {
                if (upstream != null) upstream.stop(0);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"normal,NORMAL,3", "database-failure,DATABASE_FAILURE,5",
            "payment-failure,PAYMENT_FAILURE,5", "cascade-failure,CASCADE_FAILURE,15"})
    void realHttpEndpointSubmitsValidWireEventsAndReturnsTheirIds(String path, String scenario, int count) throws Exception {
        var response = post(path);

        assertThat(response.statusCode()).isEqualTo(200);
        var summary = mapper.readTree(response.body());
        assertThat(summary.get("scenario").asString()).isEqualTo(scenario);
        assertThat(summary.get("eventCount").asInt()).isEqualTo(count);
        assertThat(submitted).hasSize(count);
        String trace = summary.get("traceId").asString();
        assertThat(UUID.fromString(trace)).isNotNull();
        for (int i = 0; i < count; i++) {
            JsonNode event = submitted.get(i);
            assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
            assertThat(summary.get("eventIds").get(i).asString()).isEqualTo(event.get("eventId").asString());
            assertThat(event.get("traceId").asString()).isEqualTo(trace);
            assertThat(event.get("serviceName").asString()).isIn("order-service", "payment-service", "inventory-service");
            assertThat(event.get("eventType").asString()).isNotBlank();
            assertThat(event.get("message").asString()).isNotBlank();
            assertThat(Instant.parse(event.get("timestamp").asString())).isNotNull();
        }
        if (scenario.equals("CASCADE_FAILURE")) {
            assertThat(submitted.subList(0, 5)).allSatisfy(event ->
                    assertThat(event.get("serviceName").asString()).isEqualTo("order-service"));
            assertThat(submitted.subList(5, 10)).allSatisfy(event ->
                    assertThat(event.get("serviceName").asString()).isEqualTo("payment-service"));
            assertThat(submitted.subList(10, 15)).allSatisfy(event ->
                    assertThat(event.get("serviceName").asString()).isEqualTo("inventory-service"));
            assertThat(Instant.parse(submitted.get(5).get("timestamp").asString()))
                    .isAfter(Instant.parse(submitted.get(4).get("timestamp").asString()));
            assertThat(Instant.parse(submitted.get(10).get("timestamp").asString()))
                    .isAfter(Instant.parse(submitted.get(9).get("timestamp").asString()));
        }
    }

    @Test
    void upstreamFailureStopsSubmissionAndReturnsPartialProgress() throws Exception {
        failAfter = 2;

        var response = post("database-failure");

        assertThat(response.statusCode()).isEqualTo(502);
        var problem = mapper.readTree(response.body());
        assertThat(problem.get("acceptedEventCount").asInt()).isEqualTo(2);
        assertThat(problem.get("acceptedEventIds").size()).isEqualTo(2);
        assertThat(submitted).hasSize(3);
        assertThat(problem.get("acceptedEventIds").get(0).asString()).isEqualTo(submitted.getFirst().get("eventId").asString());
    }

    @Test
    void basicHealthEndpointIsAvailable() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                .timeout(Duration.ofSeconds(15)).GET().build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
    }

    private HttpResponse<String> post(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/simulate/" + path))
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
