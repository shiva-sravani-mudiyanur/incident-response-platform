package com.umass.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: Maven -Pmanual-openai verify. Never included by normal Surefire test naming. */
class LiveOpenAiAnalysisIT {
    @Test
    void analyzesAnExistingIncidentUsingRealMcpAndOpenAi() throws Exception {
        if (!Boolean.getBoolean("analysis.manual.enabled")) {
            throw new IllegalStateException("Live calls require the explicit manual-openai Maven profile.");
        }
        required("OPENAI_API_KEY");
        required("OPENAI_MODEL");
        String incidentId = required("MANUAL_ANALYSIS_INCIDENT_ID");
        if (!incidentId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalStateException("MANUAL_ANALYSIS_INCIDENT_ID must be a canonical incident UUID.");
        }
        String mcpOrigin = required("DIAGNOSTICS_MCP_BASE_URL");
        try (var application = new SpringApplicationBuilder(Class.forName("com.umass.analysis.AiAnalysisServiceApplication"))
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=manual-openai",
                        "--DIAGNOSTICS_MCP_BASE_URL=" + mcpOrigin);
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(application.getBean(org.springframework.ai.chat.model.ChatModel.class))
                    .isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
            assertThat(application.getBean(com.umass.analysis.mcp.DiagnosticsGateway.class))
                    .isInstanceOf(com.umass.analysis.mcp.StreamableDiagnosticsGateway.class);
            String origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
            var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/analysis/incidents/" + incidentId))
                    .timeout(Duration.ofMinutes(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            Map<?, ?> analysis = application.getBean(ObjectMapper.class).readValue(response.body(), Map.class);
            assertThat(analysis.get("incidentId")).isEqualTo(incidentId);
            assertThat((String) analysis.get("probableCause")).isNotBlank();
            assertThat((String) analysis.get("confidence")).isIn("LOW", "MEDIUM", "HIGH");
            assertThat((List<?>) analysis.get("evidence")).isNotEmpty();
            assertThat((List<?>) analysis.get("recommendedActions")).isNotEmpty();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " locally before running this manual profile.");
        return value;
    }
}
