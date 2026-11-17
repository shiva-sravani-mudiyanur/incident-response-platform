package com.umass.analysis;

import com.sun.net.httpserver.HttpServer;
import com.umass.analysis.mcp.DiagnosticsGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real Spring AI/SDK wire contract against loopback fixtures only; never a live provider. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiClientContractTest {
    private static final String ID = "93a40209-8dc9-4c4d-9162-a298ae31306f";
    private static final String MODEL = "offline-provider/contract-model";
    private static final String PLACEHOLDER_KEY = "offline-contract-placeholder";
    private static final String DRAFT = """
            {"incidentId":"93a40209-8dc9-4c4d-9162-a298ae31306f","probableCause":"A dependency may be failing.",
             "confidence":"MEDIUM","evidenceIds":["E1","E2","E3"],"recommendedActions":["Inspect dependency health."]}
            """;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<Boolean> placeholderCredentials = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> replies = new ConcurrentLinkedQueue<>();
    private HttpServer provider;
    private ConfigurableApplicationContext application;
    private HttpClient http;
    private DiagnosticsGateway gateway;
    private DiagnosticsGateway.Session session;
    private String origin;

    @BeforeAll
    void startPrivateProviderAndRealSdkApplication() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            try {
                requests.add(mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
                paths.add(exchange.getRequestURI().getPath());
                placeholderCredentials.add(("Bearer " + PLACEHOLDER_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                Map<String, Object> reply = replies.poll();
                byte[] body = mapper.writeValueAsBytes(reply == null ? completion(Map.of("role", "assistant", "content", DRAFT), "stop") : reply);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        provider.start();
        String providerOrigin = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        application = new SpringApplicationBuilder(AiAnalysisServiceApplication.class, PrivateDiagnostics.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.ai.model.chat=openai",
                        "--spring.ai.openai.chat.base-url=" + providerOrigin,
                        "--spring.ai.openai.chat.api-key=" + PLACEHOLDER_KEY,
                        "--spring.ai.openai.chat.model=" + MODEL,
                        "--spring.ai.openai.chat.max-retries=0", "--spring.ai.openai.chat.timeout=5s",
                        "--spring.ai.openai.chat.max-completion-tokens=2048", "--spring.ai.openai.chat.store=false",
                        "--DIAGNOSTICS_MCP_BASE_URL=http://127.0.0.1:1");
        gateway = application.getBean(DiagnosticsGateway.class);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
    }

    @BeforeEach
    void resetPrivateFixtures() {
        requests.clear();
        paths.clear();
        placeholderCredentials.clear();
        replies.clear();
        reset(gateway);
        session = mock(DiagnosticsGateway.Session.class);
        when(gateway.open(anyString())).thenReturn(session);
        when(session.call("get_incident", Map.of("incidentId", ID)))
                .thenReturn(Map.of("id", ID, "rootService", "checkout", "title", "Synthetic dependency errors"));
        when(session.call("get_related_events", Map.of("incidentId", ID)))
                .thenReturn(Map.of("events", List.of(Map.of("message", "Synthetic timeout")), "truncated", false));
        when(session.call("get_service_health", Map.of("serviceName", "checkout")))
                .thenReturn(Map.of("serviceName", "checkout", "errorsLastMinute", 0));
        when(session.call("get_error_frequency", Map.of("serviceName", "checkout")))
                .thenReturn(Map.of("errorsPerSecond", 0));
    }

    @AfterAll
    void closeOnlyPrivateResources() {
        if (http != null) http.close();
        if (application != null) application.close();
        if (provider != null) provider.stop(0);
    }

    @Test
    void realSdkAcceptsAnalysisOptionsAndPreservesConfiguredModelAndLimits() throws Exception {
        ChatModel model = application.getBean(ChatModel.class);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
        // Establish that the SDK and local wire fixture work without service-supplied options.
        assertThat(model.call(new Prompt("Offline SDK contract probe")).getResult().getOutput().getText()).isEqualTo(DRAFT);
        requests.clear();
        paths.clear();
        placeholderCredentials.clear();

        var response = postAnalysis();
        assertThat(response.statusCode()).as("Actual analysis HTTP response: %s", response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("probableCause").asString()).isEqualTo("A dependency may be failing.");
        assertThat(requests).hasSize(1);
        assertConfiguredRequest(requests.getFirst());
        assertThat(paths).containsExactly("/v1/chat/completions");
        assertThat(placeholderCredentials).containsExactly(true);
        assertThat(((OpenAiChatModel) model).getOptions().getToolCallbacks()).isNullOrEmpty();
        verify(session).close();
    }

    @Test
    void realSdkToolContinuationRetainsProviderOptionsForEveryRound() throws Exception {
        replies.add(completion(Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                "id", "call-offline-1", "type", "function", "function", Map.of(
                        "name", "get_error_frequency", "arguments", "{\"serviceName\":\"checkout\"}")))), "tool_calls"));

        var response = postAnalysis();
        assertThat(response.statusCode()).as("Actual analysis HTTP response: %s", response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("evidence").size()).isEqualTo(4);
        assertThat(requests).hasSize(2).allSatisfy(this::assertConfiguredRequest);
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/chat/completions");
        assertThat(placeholderCredentials).containsExactly(true, true);
        var messages = (List<Map<String, Object>>) requests.getLast().get("messages");
        assertThat(messages).anySatisfy(message -> {
            assertThat(message).containsEntry("role", "tool").containsEntry("tool_call_id", "call-offline-1");
            assertThat((String) message.get("content")).contains("E4", "get_error_frequency", "errorsPerSecond");
        });
        verify(session).call("get_error_frequency", Map.of("serviceName", "checkout"));
        verify(session).close();
    }

    private void assertConfiguredRequest(Map<String, Object> request) {
        assertThat(request).containsEntry("model", MODEL).containsEntry("max_completion_tokens", 2048).containsEntry("store", false);
        var tools = (List<Map<String, Object>>) request.get("tools");
        assertThat(tools).extracting(tool -> ((Map<String, Object>) tool.get("function")).get("name"))
                .containsExactlyInAnyOrder("get_incident", "get_related_events", "get_service_health", "get_recent_errors", "get_error_frequency");
    }

    private HttpResponse<String> postAnalysis() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin + "/api/analysis/incidents/" + ID))
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> completion(Map<String, Object> message, String reason) {
        return Map.of("id", "chatcmpl-offline-contract", "object", "chat.completion", "created", 1, "model", MODEL,
                "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", reason)),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrivateDiagnostics {
        @Bean @Primary DiagnosticsGateway privateGateway() { return mock(DiagnosticsGateway.class); }
    }
}
