package com.umass.diagnostics.tool;

import com.umass.diagnostics.dto.DiagnosticResult;
import com.umass.diagnostics.service.DiagnosticsService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Component
public class DiagnosticTools {
    private final DiagnosticsService service;
    private final ObjectMapper mapper;

    public DiagnosticTools(DiagnosticsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @McpTool(name = "get_incident", description = "Read an incident by its full incident UUID, not an event ID.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public CallToolResult getIncident(@McpToolParam(description = "Canonical incident UUID", required = true) String incidentId) {
        return result(service.getIncident(incidentId));
    }

    @McpTool(name = "get_recent_errors", description = "Read recent ERROR/CRITICAL events by processedAt; inspect truncated and scan coverage before drawing conclusions.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public CallToolResult getRecentErrors(@McpToolParam(description = "Exact service name", required = true) String serviceName) {
        return result(service.getRecentErrors(serviceName));
    }

    @McpTool(name = "get_service_health", description = "Read event-derived service health, recent error count, active incident count, and last event timestamp.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public CallToolResult getServiceHealth(@McpToolParam(description = "Exact service name", required = true) String serviceName) {
        return result(service.getServiceHealth(serviceName));
    }

    @McpTool(name = "get_error_frequency", description = "Read the rolling 60-second failure count and average errors per second from incident-service health.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public CallToolResult getErrorFrequency(@McpToolParam(description = "Exact service name", required = true) String serviceName) {
        return result(service.getErrorFrequency(serviceName));
    }

    @McpTool(name = "get_related_events", description = "Read bounded root-service events around incident creation. Temporal proximity is not proven causality or cross-service correlation; inspect truncated.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public CallToolResult getRelatedEvents(@McpToolParam(description = "Canonical incident UUID", required = true) String incidentId) {
        return result(service.getRelatedEvents(incidentId));
    }

    private CallToolResult result(DiagnosticResult<?> result) {
        Map<?, ?> body = mapper.convertValue(result, Map.class);
        return CallToolResult.builder().structuredContent(body).addTextContent(mapper.writeValueAsString(body))
                .isError(!result.success()).build();
    }
}
