package com.umass.diagnostics.config;

import io.modelcontextprotocol.spec.McpError;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Only MCP exception response bodies are customized; normal protocol messages are untouched. */
@JacksonComponent
public class McpErrorSerializer extends ValueSerializer<McpError> {
    @Override
    public void serialize(McpError error, JsonGenerator generator, SerializationContext context) {
        // Never serialize Throwable getters, error data, or an input-bearing SDK message.
        String message = switch (error.getMessage()) {
            case "Session ID missing" -> "Session ID missing";
            case "Invalid Accept headers. Expected TEXT_EVENT_STREAM and APPLICATION_JSON" ->
                    "Invalid Accept headers. Expected TEXT_EVENT_STREAM and APPLICATION_JSON";
            case "Invalid message format" -> "Invalid message format";
            case "Unknown message type" -> "Unknown message type";
            case "Session already initialized" -> "Session already initialized";
            case "Max number of sessions reached" -> "Max number of sessions reached";
            case null, default -> "MCP session unavailable or internal server error";
        };
        generator.writeStartObject();
        generator.writeObjectPropertyStart("jsonRpcError");
        generator.writeNumberProperty("code", error.getJsonRpcError().code());
        generator.writeStringProperty("message", message);
        generator.writeEndObject();
        generator.writeEndObject();
    }
}
