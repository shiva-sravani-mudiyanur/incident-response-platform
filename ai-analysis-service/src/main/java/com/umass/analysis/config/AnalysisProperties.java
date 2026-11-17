package com.umass.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "analysis")
public record AnalysisProperties(URI diagnosticsMcpBaseUrl, Duration mcpConnectTimeout, Duration mcpRequestTimeout,
                                 int maxModelRounds, int maxToolCalls, int maxEvidenceCharacters,
                                 int maxModelResponseCharacters) {
    public AnalysisProperties {
        if (diagnosticsMcpBaseUrl == null || diagnosticsMcpBaseUrl.getHost() == null
                || !("http".equalsIgnoreCase(diagnosticsMcpBaseUrl.getScheme())
                     || "https".equalsIgnoreCase(diagnosticsMcpBaseUrl.getScheme()))
                || diagnosticsMcpBaseUrl.getUserInfo() != null || diagnosticsMcpBaseUrl.getQuery() != null
                || diagnosticsMcpBaseUrl.getFragment() != null
                || !(diagnosticsMcpBaseUrl.getPath().isEmpty() || "/".equals(diagnosticsMcpBaseUrl.getPath()))
                || diagnosticsMcpBaseUrl.getPort() == 0 || diagnosticsMcpBaseUrl.getPort() > 65535) {
            throw new IllegalArgumentException("diagnostics-mcp-base-url must be an HTTP(S) origin without credentials, path, query, or fragment");
        }
        if (maxModelRounds < 1 || maxModelRounds > 5 || maxToolCalls < 4 || maxToolCalls > 20
                || maxEvidenceCharacters < 1024 || maxEvidenceCharacters > 256000
                || maxModelResponseCharacters < 1024 || maxModelResponseCharacters > 64000) {
            throw new IllegalArgumentException("Analysis round, tool-call, and content limits must be bounded");
        }
        boundedTimeout(mcpConnectTimeout);
        boundedTimeout(mcpRequestTimeout);
    }

    private static void boundedTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofSeconds(60)) > 0
                || value.toMillis() < 1) {
            throw new IllegalArgumentException("MCP timeouts must be between 1 ms and 60 seconds");
        }
    }
}
