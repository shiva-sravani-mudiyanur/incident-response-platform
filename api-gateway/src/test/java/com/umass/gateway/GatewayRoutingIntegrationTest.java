package com.umass.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises real Gateway/Netty HTTP routing against private fixtures, never running services. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayRoutingIntegrationTest {
    private final Map<String, Backend> backends = new LinkedHashMap<>();
    private ConfigurableApplicationContext application;
    private HttpClient http;
    private String origin;

    @BeforeAll
    void startPrivateBackendsAndGateway() throws Exception {
        for (String name : new String[] {"event-service", "incident-service", "ai-analysis-service", "service-simulator"}) {
            backends.put(name, new Backend(name));
        }
        application = new SpringApplicationBuilder(ApiGatewayApplication.class)
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--GATEWAY_EVENT_SERVICE_URL=" + backends.get("event-service").origin(),
                        "--GATEWAY_INCIDENT_SERVICE_URL=" + backends.get("incident-service").origin(),
                        "--GATEWAY_ANALYSIS_SERVICE_URL=" + backends.get("ai-analysis-service").origin(),
                        "--GATEWAY_SIMULATOR_URL=" + backends.get("service-simulator").origin());
        origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    void closeOnlyPrivateResources() {
        if (http != null) http.close();
        if (application != null) application.close();
        backends.values().forEach(Backend::close);
    }

    @ParameterizedTest
    @MethodSource("routeRequests")
    void configurationRoutesOriginalRequestToExpectedBackend(String backend, String method, String path, String body, int status) throws Exception {
        var response = send(method, path, body);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Backend-Service")).contains(backend);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/plain; charset=UTF-8");
        assertThat(response.body()).isEqualTo(echo(backend, method, path, body));
    }

    static Stream<Arguments> routeRequests() {
        return Stream.of(
                Arguments.of("event-service", "POST", "/api/events?source=smoke&trace=a%20b", "{\"synthetic\":true}", 202),
                Arguments.of("event-service", "POST", "/api/events/fixture-child", "{\"synthetic\":true}", 202),
                Arguments.of("incident-service", "GET", "/api/incidents?status=OPEN&status=INVESTIGATING", "", 200),
                Arguments.of("incident-service", "PATCH", "/api/incidents/fixture-id/status", "{\"status\":\"RESOLVED\"}", 200),
                Arguments.of("incident-service", "GET", "/api/services", "", 200),
                Arguments.of("incident-service", "GET", "/api/services/order-service/health?probe=a%20b", "", 200),
                Arguments.of("ai-analysis-service", "POST", "/api/analysis", "", 200),
                Arguments.of("ai-analysis-service", "POST", "/api/analysis/incidents/fixture-id", "", 200),
                Arguments.of("service-simulator", "POST", "/simulate", "", 200),
                Arguments.of("service-simulator", "POST", "/simulate/database-failure", "{}", 200),
                Arguments.of("incident-service", "GET", "/api/incidents/upstream-invalid", "", 400),
                Arguments.of("ai-analysis-service", "POST", "/api/analysis/upstream-unavailable", "", 503));
    }

    @Test
    void exposesSanitizedGatewayHealth() throws Exception {
        var response = send("GET", "/actuator/health", "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"components\"", "\"details\"");
        assertThat(response.headers().firstValue("X-Backend-Service")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/events-extra", "/api/incidents-extra", "/api/services-extra", "/api/analysis-extra",
            "/simulate-extra", "/mcp", "/actuator", "/actuator/env", "/actuator/heapdump", "/actuator/loggers",
            "/actuator/configprops", "/actuator/gateway/routes", "/actuator/shutdown"})
    void doesNotExposeUnconfiguredPathsOrManagementEndpoints(String path) throws Exception {
        var response = send("GET", path, "");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Backend-Service")).isEmpty();
        assertThat(response.body()).doesNotContain("stackTrace", "exception", "localhost", "127.0.0.1");
    }

    @ParameterizedTest
    @MethodSource("administrativeWrites")
    void gatewayAdministrativeWritesAreNotExposed(String method, String path) throws Exception {
        assertThat(send(method, path, "{}").statusCode()).isEqualTo(404);
    }

    static Stream<Arguments> administrativeWrites() {
        return Stream.of(Arguments.of("POST", "/actuator/gateway/routes/injected"),
                Arguments.of("DELETE", "/actuator/gateway/routes/events"),
                Arguments.of("POST", "/actuator/gateway/refresh"),
                Arguments.of("POST", "/actuator/shutdown"));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Request-Id", "gateway-contract")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String echo(String name, String method, String path, String body) {
        return name + "\n" + method + "\n" + path + "\napplication/json\ngateway-contract\n" + body;
    }

    private static final class Backend {
        private final HttpServer server;

        Backend(String name) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String path = exchange.getRequestURI().toString();
                    String result = name + "\n" + exchange.getRequestMethod() + "\n" + path + "\n"
                            + exchange.getRequestHeaders().getFirst("Content-Type") + "\n"
                            + exchange.getRequestHeaders().getFirst("X-Request-Id") + "\n" + body;
                    byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                    int status = name.equals("event-service") ? 202 : 200;
                    if (exchange.getRequestURI().getPath().endsWith("/upstream-invalid")) status = 400;
                    if (exchange.getRequestURI().getPath().endsWith("/upstream-unavailable")) status = 503;
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.getResponseHeaders().set("X-Backend-Service", name);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void close() { server.stop(0); }
    }
}
