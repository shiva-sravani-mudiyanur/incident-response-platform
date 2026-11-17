package com.umass.diagnostics;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagnosticsMcpIntegrationTest {
    private final UUID incidentId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer incidentStub;
    private ConfigurableApplicationContext application;
    private McpSyncClient client;
    private ObjectMapper mapper;

    @BeforeAll
    void startRealStreamableServerAndClient() throws Exception {
        assertThatCode(() -> Class.forName("com.umass.diagnostics.DiagnosticsMcpServerApplication"))
                .as("The diagnostics MCP application must exist").doesNotThrowAnyException();
        incidentStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        incidentStub.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            Instant now = Instant.now();
            if (path.contains("unavailable-service")) {
                status = 503;
                body = "private-downstream-connection-details";
            } else if (path.contains("missing-service")) {
                status = 404;
                body = "not found";
            } else if (path.equals("/api/incidents/" + incidentId)) {
                body = """
                        {"id":"%s","title":"Elevated error rate","status":"OPEN","severity":"HIGH",
                         "rootService":"order-service","createdAt":"%s","updatedAt":"%s","errorCount":5}
                        """.formatted(incidentId, now.minusSeconds(10), now.minusSeconds(10));
            } else if (path.equals("/api/services/order-service/health")) {
                body = """
                        {"serviceName":"order-service","status":"DEGRADED","errorsLastMinute":2,
                         "activeIncidentCount":0,"lastEventTimestamp":"%s"}
                        """.formatted(now.minusSeconds(2));
            } else if (path.equals("/api/services/order-service/events")) {
                body = """
                        {"serviceName":"order-service","page":0,"size":100,"totalElements":1,"totalPages":1,
                         "events":[{"eventId":"%s","serviceName":"order-service","eventType":"DATABASE_ERROR",
                         "severity":"ERROR","message":"Order database timeout","timestamp":"%s",
                         "traceId":"integration-trace","processedAt":"%s"}]}
                        """.formatted(eventId, now.minusSeconds(2), now.minusSeconds(2));
            } else {
                status = 404;
                body = "not found";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        incidentStub.start();
        application = new SpringApplicationBuilder(Class.forName("com.umass.diagnostics.DiagnosticsMcpServerApplication"))
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--INCIDENT_SERVICE_BASE_URL=http://127.0.0.1:" + incidentStub.getAddress().getPort());
        mapper = application.getBean(ObjectMapper.class);
        String origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
        var transport = HttpClientStreamableHttpTransport.builder(origin).endpoint("/mcp")
                .connectTimeout(Duration.ofSeconds(5)).openConnectionOnStartup(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15)).build();
        var initialization = client.initialize();
        assertThat(initialization.serverInfo().name()).isEqualTo("diagnostics-mcp-server");
    }

    @BeforeEach
    void resetRecordedRequests() {
        requests.clear();
    }

    @AfterAll
    void stopPrivateServers() {
        try {
            if (client != null) client.close();
        } finally {
            try {
                if (application != null) application.close();
            } finally {
                if (incidentStub != null) incidentStub.stop(0);
            }
        }
    }

    @Test
    void discoversExactlyFiveReadOnlyToolsWithRequiredInputSchemas() {
        assertThat(application.getEnvironment().getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
        var tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "get_incident", "get_recent_errors", "get_service_health", "get_error_frequency", "get_related_events");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
            assertThat(tool.annotations().idempotentHint()).isTrue();
            String parameter = tool.name().equals("get_incident") || tool.name().equals("get_related_events")
                    ? "incidentId" : "serviceName";
            assertThat((List<?>) tool.inputSchema().get("required")).hasSize(1);
            assertThat(((List<?>) tool.inputSchema().get("required")).getFirst()).isEqualTo(parameter);
            assertThat(tool.inputSchema().get("type")).isEqualTo("object");
        });
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"get_incident,incidentId", "get_service_health,serviceName", "get_error_frequency,serviceName",
            "get_recent_errors,serviceName", "get_related_events,incidentId"})
    void invokesAllToolsOverActualStreamableHttp(String name, String parameter) {
        String value = parameter.equals("incidentId") ? incidentId.toString() : "order-service";
        var result = client.callTool(new McpSchema.CallToolRequest(name, Map.of(parameter, value)));
        assertThat(result.isError()).isFalse();
        Map<String, Object> envelope = object(result.structuredContent());
        assertThat(envelope).containsEntry("success", true);
        assertThat(envelope.get("data")).isNotNull();
        assertThat(envelope.get("error")).isNull();
        assertThat(result.content()).isNotEmpty();
        assertThat(requests).isNotEmpty().allMatch(request -> request.startsWith("GET /api/"));
        if (name.equals("get_incident")) {
            assertThat(object(envelope.get("data"))).containsEntry("id", incidentId.toString());
        } else if (name.equals("get_service_health")) {
            assertThat(object(envelope.get("data"))).containsEntry("status", "DEGRADED")
                    .containsEntry("serviceName", "order-service");
        } else if (name.equals("get_recent_errors") || name.equals("get_related_events")) {
            Map<String, Object> data = object(envelope.get("data"));
            assertThat((List<?>) data.get("events")).hasSize(1);
            assertThat(object(((List<?>) data.get("events")).getFirst())).containsEntry("eventId", eventId.toString());
            assertThat(data).containsEntry("truncated", false);
        }
    }

    @ParameterizedTest
    @CsvSource({"get_incident,incidentId,1-1-1-1-1", "get_related_events,incidentId,not-a-uuid",
            "get_service_health,serviceName,..", "get_recent_errors,serviceName,../other",
            "get_error_frequency,serviceName,../other"})
    void invalidInputsReturnStructuredToolErrorsWithoutUpstreamCalls(String tool, String parameter, String input) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, Map.of(parameter, input)));
        assertThat(result.isError()).isTrue();
        Map<String, Object> envelope = object(result.structuredContent());
        assertThat(envelope).containsEntry("success", false);
        assertThat(object(envelope.get("error"))).containsEntry("code", "INVALID_INPUT");
        assertThat(requests).isEmpty();
    }

    @Test
    void unavailableDownstreamIsAnMcpToolErrorNotEmptySuccess() {
        var result = client.callTool(new McpSchema.CallToolRequest("get_service_health",
                Map.of("serviceName", "unavailable-service")));
        assertThat(result.isError()).isTrue();
        Map<String, Object> envelope = object(result.structuredContent());
        assertThat(envelope).containsEntry("success", false);
        assertThat(object(envelope.get("error"))).containsEntry("code", "DOWNSTREAM_UNAVAILABLE");
        assertThat(mapper.writeValueAsString(envelope)).doesNotContain("private-downstream-connection-details");
    }

    @Test
    void unknownServiceIsARecognizableNotFoundToolError() {
        var result = client.callTool(new McpSchema.CallToolRequest("get_service_health",
                Map.of("serviceName", "missing-service")));
        assertThat(result.isError()).isTrue();
        assertThat(object(object(result.structuredContent()).get("error"))).containsEntry("code", "NOT_FOUND");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"get_incident", "get_recent_errors", "get_service_health",
            "get_error_frequency", "get_related_events"})
    void missingArgumentIsRejectedWithoutAnUpstreamRequest(String tool) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void documentedRawHttpInitializationAndToolInvocationWorkWithoutAnSdkClient() throws Exception {
        String origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
        try (var http = HttpClient.newHttpClient()) {
            var initialize = http.send(rawRequest(origin).POST(HttpRequest.BodyPublishers.ofString("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                     "capabilities":{},"clientInfo":{"name":"postman-check","version":"1.0"}}}
                    """)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(initialize.statusCode()).isEqualTo(200);
            String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
            String protocol = (String) object(rpcBody(initialize).get("result")).get("protocolVersion");
            try {
                var notification = http.send(rawRequest(origin).header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", protocol).POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(notification.statusCode()).isEqualTo(202);
                var list = http.send(rawRequest(origin).header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", protocol).POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(list.statusCode()).isEqualTo(200);
                assertThat((List<?>) object(rpcBody(list).get("result")).get("tools")).hasSize(5);
                var call = http.send(rawRequest(origin).header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", protocol).POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                                 "name":"get_service_health","arguments":{"serviceName":"order-service"}}}
                                """)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(call.statusCode()).isEqualTo(200);
                Map<String, Object> result = object(rpcBody(call).get("result"));
                assertThat(result).containsEntry("isError", false);
                assertThat(object(result.get("structuredContent"))).containsEntry("success", true);
            } finally {
                var termination = http.send(rawRequest(origin).header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", protocol).DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(termination.statusCode()).isEqualTo(200);
            }
        }
    }

    private HttpRequest.Builder rawRequest(String origin) {
        return HttpRequest.newBuilder(URI.create(origin + "/mcp")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
    }

    private Map<String, Object> rpcBody(HttpResponse<String> response) {
        String json = response.body().strip();
        if (!json.startsWith("{")) {
            json = response.body().lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip()).filter(line -> line.contains("\"jsonrpc\""))
                    .findFirst().orElseThrow();
        }
        return object(mapper.readValue(json, Map.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return mapper.convertValue(value, Map.class);
    }
}
