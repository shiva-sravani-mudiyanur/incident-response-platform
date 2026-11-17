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
    private volatile String mode = "normal";

    @BeforeEach
    void startPrivateMcpFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        var properties = new AnalysisProperties(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 3, 8, 64000, 16000);
        gateway = (DiagnosticsGateway) Class.forName("com.umass.analysis.mcp.StreamableDiagnosticsGateway")
                .getConstructor(AnalysisProperties.class, ObjectMapper.class).newInstance(properties, mapper);
    }

    @AfterEach
    void stopPrivateFixture() {
        if (server != null) server.stop(0);
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
