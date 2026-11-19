package com.umass.analysis;

import com.umass.analysis.exception.AnalysisException;
import com.umass.analysis.mcp.DiagnosticsGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisApplicationIntegrationTest {
    private final String incidentId = UUID.randomUUID().toString();
    private ConfigurableApplicationContext application;
    private HttpClient http;
    private ChatModel model;
    private DiagnosticsGateway gateway;
    private DiagnosticsGateway.Session session;
    private ObjectMapper mapper;
    private String origin;

    @BeforeAll
    void startOnlyWithMockChatModelAndPrivateDiagnostics() throws Exception {
        assertThatCode(() -> Class.forName("com.umass.analysis.AiAnalysisServiceApplication")).doesNotThrowAnyException();
        application = new SpringApplicationBuilder(Class.forName("com.umass.analysis.AiAnalysisServiceApplication"), Mocks.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.ai.model.chat=none",
                        "--spring.ai.openai.chat.api-key=", "--spring.ai.openai.chat.model=mock-model-never-called",
                        "--DIAGNOSTICS_MCP_BASE_URL=http://127.0.0.1:1",
                        // Native Windows JDK CPU gauges can block in OperatingSystemImpl.getCpuLoad0.
                        // Keep this HTTP fixture deterministic; production metric configuration is unchanged.
                        "--management.metrics.enable.system.cpu=false", "--management.metrics.enable.process.cpu=false");
        model = application.getBean(ChatModel.class);
        gateway = application.getBean(DiagnosticsGateway.class);
        mapper = application.getBean(ObjectMapper.class);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
    }

    @BeforeEach
    void resetMocks() {
        reset(model, gateway);
        session = mock(DiagnosticsGateway.Session.class);
        when(gateway.open(anyString())).thenReturn(session);
        String now = Instant.now().toString();
        when(session.call(eq("get_incident"), anyMap())).thenReturn(data("""
                {"id":"%s","title":"Order database errors","status":"OPEN","severity":"HIGH",
                 "rootService":"order-service","createdAt":"%s","updatedAt":"%s","errorCount":5}
                """.formatted(incidentId, now, now)));
        when(session.call(eq("get_related_events"), anyMap())).thenReturn(data("""
                {"incidentId":"%s","rootService":"order-service","relationshipType":"ROOT_SERVICE_TIME_WINDOW",
                 "explanation":"Temporal proximity, not proven causality","windowStart":"%s","windowEnd":"%s",
                 "timeBasis":"PROCESSED_AT","scannedEvents":1,"scannedPages":1,"truncated":false,"hasMore":false,
                 "events":[{"eventId":"%s","serviceName":"order-service","eventType":"DATABASE_ERROR",
                 "severity":"ERROR","message":"Database timeout","timestamp":"%s","processedAt":"%s","traceId":"test-trace"}]}
                """.formatted(incidentId, now, now, UUID.randomUUID(), now, now)));
        when(session.call(eq("get_service_health"), anyMap())).thenReturn(data("""
                {"serviceName":"order-service","status":"DOWN","errorsLastMinute":0,
                 "activeIncidentCount":1,"lastEventTimestamp":"%s"}
                """.formatted(now)));
        when(session.call(eq("get_recent_errors"), anyMap())).thenReturn(data("""
                {"serviceName":"order-service","windowStart":"%s","windowEnd":"%s","timeBasis":"PROCESSED_AT",
                 "events":[],"scannedEvents":0,"scannedPages":1,"truncated":false,"hasMore":false}
                """.formatted(now, now)));
        when(model.call(any(Prompt.class))).thenReturn(response(validDraft()));
    }

    @AfterAll
    void closeLocalApplication() {
        if (http != null) http.close();
        if (application != null) application.close();
    }

    @Test
    void postReturnsGroundedAnalysisWithObservedEvidenceAndRecommendations() throws Exception {
        var response = post(incidentId);
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> analysis = data(response.body());
        assertThat(analysis).containsEntry("incidentId", incidentId).containsEntry("confidence", "MEDIUM");
        assertThat((String) analysis.get("probableCause")).contains("database");
        assertThat((List<?>) analysis.get("evidence")).hasSize(3);
        assertThat((List<?>) analysis.get("recommendedActions")).hasSize(1);
        verify(model).call(any(Prompt.class));
        verify(session).close();
    }

    @Test
    void unknownIncidentIs404AndNeverCallsModel() throws Exception {
        when(session.call(eq("get_incident"), anyMap())).thenThrow(new AnalysisException(
                HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        assertThat(post(incidentId).statusCode()).isEqualTo(404);
        verifyNoInteractions(model);
    }

    @Test
    void unavailableMcpIs503AndNeverCallsModel() throws Exception {
        when(gateway.open(anyString())).thenThrow(new AnalysisException(HttpStatus.SERVICE_UNAVAILABLE,
                "DIAGNOSTICS_UNAVAILABLE", "Diagnostics service unavailable."));
        var response = post(incidentId);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(data(response.body())).containsEntry("code", "DIAGNOSTICS_UNAVAILABLE");
        verifyNoInteractions(model);
    }

    @Test
    void providerFailureIsSanitizedAnd503() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("private-provider-details"));
        var response = post(incidentId);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("private-provider-details");
    }

    @Test
    void malformedModelResponseIs502NotInventedAnalysis() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(response("not valid analysis JSON"));
        assertThat(post(incidentId).statusCode()).isEqualTo(502);
    }

    @Test
    void shortUuidIs400BeforeMcpOrModel() throws Exception {
        assertThat(post("1-1-1-1-1").statusCode()).isEqualTo(400);
        verifyNoInteractions(gateway, model);
    }

    @Test
    void testsDoNotInstantiateAnOpenAiChatModel() throws Exception {
        assertThat(mockingDetails(model).isMock()).isTrue();
        assertThat(application.getBeansOfType(org.springframework.ai.openai.OpenAiChatModel.class)).isEmpty();
        var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data(response.body())).containsEntry("status", "UP");
    }

    @Test
    void exposesOnlyRequestedReadOnlyActuatorAllowlist() throws Exception {
        var environment = application.getEnvironment();
        assertThat(environment.getProperty("management.endpoints.access.default")).isEqualTo("none");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,metrics,prometheus");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("ai-analysis-service");
        for (String endpoint : List.of("health", "info", "metrics", "prometheus")) {
            assertThat(environment.getProperty("management.endpoint." + endpoint + ".access")).isEqualTo("read-only");
            assertThat(get("/actuator/" + endpoint).statusCode()).as(endpoint).isEqualTo(200);
            var write = http.send(HttpRequest.newBuilder(URI.create(origin + "/actuator/" + endpoint))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(write.statusCode()).as("write " + endpoint).isEqualTo(405);
        }
        for (String endpoint : List.of("", "/env", "/configprops", "/beans", "/loggers", "/mappings", "/heapdump", "/threaddump", "/shutdown")) {
            assertThat(get("/actuator" + endpoint).statusCode()).as(endpoint).isEqualTo(404);
        }
        verifyNoInteractions(model, gateway);
    }

    @Test
    void prometheusExportsAnalysisOutcomesWithOnlyApplicationAndOutcomeTags() throws Exception {
        assertThat(post(incidentId).statusCode()).isEqualTo(200);
        assertThat(post("invalid").statusCode()).isEqualTo(400);
        var response = get("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("analysis_duration_seconds_count", "analysis_duration_seconds_sum",
                "analysis_requests_total", "analysis_errors_total", "outcome=\"success\"", "outcome=\"error\"");
        var samples = response.body().lines().filter(line -> line.startsWith("analysis_")).toList();
        assertThat(samples).isNotEmpty().allSatisfy(line -> {
            assertThat(line).contains("application=\"ai-analysis-service\"").doesNotContain(incidentId, "order-service", "test-trace");
            String labels = line.substring(line.indexOf('{') + 1, line.indexOf('}'));
            assertThat(java.util.Arrays.stream(labels.split(",")).map(label -> label.substring(0, label.indexOf('='))).toList())
                    .containsExactlyInAnyOrder("application", "outcome");
        });
        verify(model).call(any(Prompt.class));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String validDraft() {
        return """
                {"incidentId":"%s","probableCause":"Observed database timeout events suggest a database access issue; root cause is not proven.",
                 "confidence":"MEDIUM","evidenceIds":["E1","E2","E3"],
                 "recommendedActions":["Inspect database timeout and connection-pool metrics before changing configuration."]}
                """.formatted(incidentId);
    }

    private ChatResponse response(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private HttpResponse<String> post(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin + "/api/analysis/incidents/" + id))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(String json) { return mapper.readValue(json, Map.class); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Mocks {
        @Bean @Primary
        ChatModel mockChatModel() { return mock(ChatModel.class); }
        @Bean @Primary
        DiagnosticsGateway mockDiagnosticsGateway() { return mock(DiagnosticsGateway.class); }
    }
}
