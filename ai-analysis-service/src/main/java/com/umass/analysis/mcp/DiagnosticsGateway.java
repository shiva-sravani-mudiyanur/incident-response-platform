package com.umass.analysis.mcp;

import java.util.Map;

/** One isolated read-only MCP session per incident analysis. */
public interface DiagnosticsGateway {
    Session open(String incidentId);

    interface Session extends AutoCloseable {
        Map<String, Object> call(String toolName, Map<String, Object> arguments);
        @Override
        void close();
    }
}
