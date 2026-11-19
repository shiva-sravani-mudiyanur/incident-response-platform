package com.umass.analysis.mcp;

import com.umass.analysis.config.AnalysisProperties;
import com.umass.analysis.exception.AnalysisException;
import com.umass.analysis.resilience.McpReadResilience;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class StreamableDiagnosticsGateway implements DiagnosticsGateway {
    private static final Map<String, String> PARAMETERS = Map.of(
            "get_incident", "incidentId", "get_related_events", "incidentId",
            "get_service_health", "serviceName", "get_recent_errors", "serviceName", "get_error_frequency", "serviceName");
    private final AnalysisProperties properties;
    private final ObjectMapper mapper;
    private final McpReadResilience resilience;

    public StreamableDiagnosticsGateway(AnalysisProperties properties, ObjectMapper mapper, McpReadResilience resilience) {
        this.properties = properties;
        this.mapper = mapper;
        this.resilience = resilience;
    }

    @Override
    public Session open(String incidentId) {
        if (incidentId == null || !incidentId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new AnalysisException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_ID", "Provide a canonical incident UUID.");
        }
        McpSyncClient client = null;
        try {
            var transport = HttpClientStreamableHttpTransport.builder(properties.diagnosticsMcpBaseUrl().toString())
                    .endpoint("/mcp").connectTimeout(properties.mcpConnectTimeout()).openConnectionOnStartup(false).build();
            client = McpClient.sync(transport).requestTimeout(properties.mcpRequestTimeout())
                    .initializationTimeout(properties.mcpRequestTimeout()).build();
            McpSyncClient openedClient = client;
            return resilience.once(() -> {
                openedClient.initialize();
                var tools = openedClient.listTools().tools();
                for (String name : PARAMETERS.keySet()) {
                    var tool = tools.stream().filter(candidate -> name.equals(candidate.name())).findFirst().orElseThrow();
                    if (tool.annotations() == null || !Boolean.TRUE.equals(tool.annotations().readOnlyHint())
                            || !Boolean.FALSE.equals(tool.annotations().destructiveHint())) throw unavailable();
                }
                return new PinnedSession(openedClient, incidentId);
            });
        } catch (RuntimeException exception) {
            closeQuietly(client);
            throw unavailable();
        }
    }

    private final class PinnedSession implements Session {
        private final McpSyncClient client;
        private final String incidentId;
        private String rootService;
        private int calls;
        private boolean closed;

        private PinnedSession(McpSyncClient client, String incidentId) {
            this.client = client;
            this.incidentId = incidentId;
        }

        @Override
        public Map<String, Object> call(String toolName, Map<String, Object> arguments) {
            String parameter = toolName == null ? null : PARAMETERS.get(toolName);
            if (closed || parameter == null || arguments == null || !arguments.keySet().equals(Set.of(parameter))
                    || !(arguments.get(parameter) instanceof String value)) throw policyViolation();
            if (parameter.equals("incidentId")) {
                if (!incidentId.equalsIgnoreCase(value)) throw policyViolation();
            } else if (rootService == null || !rootService.equals(value)) throw policyViolation();
            if (rootService == null && !toolName.equals("get_incident")) throw policyViolation();
            if (++calls > properties.maxToolCalls()) throw policyViolation();
            try {
                var request = McpSchema.CallToolRequest.builder(toolName).arguments(Map.copyOf(arguments)).build();
                var response = resilience.read(() -> client.callTool(request));
                Map<String, Object> envelope = object(response.structuredContent());
                if (!Boolean.FALSE.equals(response.isError()) || !Boolean.TRUE.equals(envelope.get("success"))) {
                    Object error = envelope.get("error");
                    if (toolName.equals("get_incident") && error instanceof Map<?, ?> details
                            && "NOT_FOUND".equals(details.get("code"))) {
                        throw new AnalysisException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found.");
                    }
                    throw unavailable();
                }
                Map<String, Object> data = object(envelope.get("data"));
                if (mapper.writeValueAsString(data).length() > properties.maxEvidenceCharacters()) throw unavailable();
                if (toolName.equals("get_incident")) {
                    if (!(data.get("id") instanceof String id) || !incidentId.equalsIgnoreCase(id)
                            || !(data.get("rootService") instanceof String name) || !validName(name)
                            || rootService != null && !rootService.equals(name)) throw unavailable();
                    rootService = name;
                } else if (toolName.equals("get_related_events")) {
                    if (!rootService.equals(data.get("rootService")) || !(data.get("incidentId") instanceof String id)
                            || !incidentId.equalsIgnoreCase(id)) throw unavailable();
                } else if (!rootService.equals(data.get("serviceName"))) throw unavailable();
                return Collections.unmodifiableMap(new LinkedHashMap<>(data));
            } catch (AnalysisException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw unavailable();
            }
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(client);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw unavailable();
        return mapper.convertValue(value, Map.class);
    }

    private static boolean validName(String value) {
        return !value.isBlank() && value.length() <= 100 && !value.equals(".") && !value.equals("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.chars().noneMatch(Character::isISOControl);
    }

    private static AnalysisException unavailable() {
        return new AnalysisException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Diagnostics MCP service is unavailable or returned invalid data.");
    }

    private static AnalysisException policyViolation() {
        return new AnalysisException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE", "Requested diagnostic operation is outside the read-only incident scope or budget.");
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client != null) {
            try { client.close(); } catch (RuntimeException ignored) { /* Best-effort local session cleanup; never hide the analysis outcome. */ }
        }
    }
}
