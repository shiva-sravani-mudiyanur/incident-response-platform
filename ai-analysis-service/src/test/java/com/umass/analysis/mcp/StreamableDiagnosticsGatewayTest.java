package com.umass.analysis.mcp;

import com.sun.net.httpserver.HttpServer;
import com.umass.analysis.config.AnalysisProperties;
import com.umass.analysis.exception.AnalysisException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamableDiagnosticsGatewayTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String incidentId = UUID.randomUUID().toString();
    private final List<String> toolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private HttpServer server;
    private DiagnosticsGateway gateway;
    private java.util.concurrent.ExecutorService resilienceExecutor;
    private java.util.concurrent.ExecutorService fixtureExecutor;
    private final java.util.concurrent.atomic.AtomicInteger initializations = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long initializeDelayMillis;
    private volatile long firstToolDelayMillis;
    private volatile String mode = "normal";

    @BeforeEach
    void startPrivateMcpFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(fixtureExecutor);
        server.createContext("/mcp", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            Map<?, ?> request = mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            String method = (String) request.get("method");
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result;
            if (method.equals("initialize")) {
                initializations.incrementAndGet();
                pause(initializeDelayMillis);
                exchange.getResponseHeaders().set("Mcp-Session-Id", "private-test-session");
                result = Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "diagnostics-mcp-server", "version", "0.0.1"));
            } else if (method.equals("tools/list")) {
                var tools = new ArrayList<Map<String, Object>>();
                for (String name : List.of("get_incident", "get_related_events", "get_service_health", "get_recent_errors", "get_error_frequency")) {
                    String parameter = name.equals("get_incident") || name.equals("get_related_events") ? "incidentId" : "serviceName";
                    tools.add(Map.of("name", name, "description", "Read-only fixture",
                            "inputSchema", Map.of("type", "object", "properties", Map.of(parameter, Map.of("type", "string")),
                                    "required", List.of(parameter)),
                            "annotations", Map.of("readOnlyHint", !mode.equals("unsafe"), "destructiveHint", false, "idempotentHint", true)));
                }
                result = Map.of("tools", tools);
            } else if (method.equals("tools/call")) {
                Map<?, ?> params = (Map<?, ?>) request.get("params");
                String name = (String) params.get("name");
                toolCalls.add(name);
                if (toolCalls.size() == 1) pause(firstToolDelayMillis);
                Map<String, Object> envelope;
                boolean error = mode.equals("missing") || mode.equals("unavailable");
                if (error) {
                    envelope = Map.of("success", false, "error", Map.of("code", mode.equals("missing") ? "NOT_FOUND" : "DOWNSTREAM_UNAVAILABLE",
                            "message", "private provider connection details"));
                } else {
                    Map<String, Object> data = name.equals("get_incident")
                            ? Map.of("id", incidentId, "rootService", "order-service", "title", "Failure")
                            : Map.of("serviceName", "order-service", "status", "DOWN", "errorsLastMinute", 5);
                    envelope = Map.of("success", true, "data", data);
                }
                result = Map.of("isError", error, "structuredContent", envelope,
                        "content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(envelope))));
            } else {
                throw new IllegalStateException("Unexpected fixture method");
            }
            byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        configureGateway(Duration.ofSeconds(5), Map.of());
    }

    private void configureGateway(Duration requestTimeout, Map<String, Object> overrides) {
        if (resilienceExecutor != null) resilienceExecutor.shutdownNow();
        var properties = new AnalysisProperties(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), requestTimeout, 3, 8, 64000, 16000);
        var values = new java.util.HashMap<String, Object>();
        overrides.forEach((key, value) -> values.put("analysis.resilience." + key, value));
        var resilienceProperties = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values))
                .bindOrCreate("analysis.resilience", com.umass.analysis.resilience.McpResilienceProperties.class);
        var configuration = new com.umass.analysis.resilience.McpResilienceConfiguration();
        resilienceExecutor = configuration.mcpReadExecutor(resilienceProperties);
        var factory = new org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory(
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(), null);
        configuration.mcpCircuitCustomizer(resilienceProperties, resilienceExecutor).customize(factory);
        gateway = new StreamableDiagnosticsGateway(properties, mapper,
                new com.umass.analysis.resilience.McpReadResilience(factory, resilienceProperties));
    }

    @AfterEach
    void stopPrivateFixture() {
        if (server != null) server.stop(0);
        if (fixtureExecutor != null) fixtureExecutor.shutdownNow();
        if (resilienceExecutor != null) resilienceExecutor.shutdownNow();
    }

    private static void pause(long milliseconds) {
        try { Thread.sleep(milliseconds); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    @Test void retriesTimedOutReadWithoutReinitializingOrChangingScope() {
        configureGateway(Duration.ofMillis(500), Map.of("operation-timeout", "2s", "retry-delay", "0ms"));
        firstToolDelayMillis = 900;
        try (var session = gateway.open(incidentId)) {
            assertThat(session.call("get_incident", Map.of("incidentId", incidentId))).containsEntry("id", incidentId);
        }
        assertThat(toolCalls).containsExactly("get_incident", "get_incident");
        assertThat(initializations).hasValue(1);
    }

    @Test void overallDeadlineBoundsSlowInitializationAndDoesNotReplayHandshake() {
        configureGateway(Duration.ofSeconds(5), Map.of("operation-timeout", "200ms"));
        initializeDelayMillis = 2000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.open(incidentId)).isInstanceOfSatisfying(AnalysisException.class,
                error -> assertThat(error.status().value()).isEqualTo(503));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(initializations).hasValue(1);
        assertThat(toolCalls).isEmpty();
    }

    @Test
    void initializesMcpAndReadsIncidentThenItsServiceThroughTools() {
        try (var session = gateway.open(incidentId)) {
            assertThat(session.call("get_incident", Map.of("incidentId", incidentId))).containsEntry("id", incidentId);
            assertThat(session.call("get_service_health", Map.of("serviceName", "order-service"))).containsEntry("status", "DOWN");
        }
        assertThat(toolCalls).containsExactly("get_incident", "get_service_health");
    }

    @Test
    void rejectsMutationAndCrossIncidentOrCrossServiceCallsBeforeNetworkExecution() {
        try (var session = gateway.open(incidentId)) {
            session.call("get_incident", Map.of("incidentId", incidentId));
            assertThatThrownBy(() -> session.call(null, Map.of())).isInstanceOf(AnalysisException.class);
            for (var call : List.of(Map.of("name", "resolve_incident", "key", "incidentId", "value", incidentId),
                    Map.of("name", "get_incident", "key", "incidentId", "value", UUID.randomUUID().toString()),
                    Map.of("name", "get_service_health", "key", "serviceName", "value", "unrelated-service"))) {
                assertThatThrownBy(() -> session.call(call.get("name"), Map.of(call.get("key"), call.get("value"))))
                        .isInstanceOf(AnalysisException.class);
            }
        }
        assertThat(toolCalls).containsExactly("get_incident");
    }

    @Test
    void incidentNotFoundIs404AndDoesNotLeakUpstreamDetails() {
        mode = "missing";
        try (var session = gateway.open(incidentId)) {
            assertThatThrownBy(() -> session.call("get_incident", Map.of("incidentId", incidentId)))
                    .isInstanceOfSatisfying(AnalysisException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(404);
                        assertThat(exception.getMessage()).doesNotContain("private provider");
                    });
        }
    }

    @Test
    void downstreamFailureIsSanitizedAndUnavailable() {
        mode = "unavailable";
        try (var session = gateway.open(incidentId)) {
            assertThatThrownBy(() -> session.call("get_incident", Map.of("incidentId", incidentId)))
                    .isInstanceOfSatisfying(AnalysisException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(503);
                        assertThat(exception.getMessage()).doesNotContain("private provider");
                    });
        }
    }

    @Test
    void deniesServersThatDoNotDeclareReadOnlyTools() {
        mode = "unsafe";
        assertThatThrownBy(() -> gateway.open(incidentId)).isInstanceOf(AnalysisException.class);
        assertThat(toolCalls).isEmpty();
    }
}
