package com.umass.incident.observability;

import org.slf4j.MDC;

/** Restores a caller's trace even for nested calls and events with no trace. */
public final class EventTraceScope implements AutoCloseable {
    private final String previous = MDC.get("traceId");
    public EventTraceScope(String traceId) {
        if (traceId == null) MDC.remove("traceId");
        else MDC.put("traceId", traceId);
    }
    @Override public void close() {
        if (previous == null) MDC.remove("traceId");
        else MDC.put("traceId", previous);
    }
}
