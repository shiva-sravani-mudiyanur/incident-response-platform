package com.umass.event.service;

import org.slf4j.MDC;

/** Stack-safe scope: a missing event trace must not inherit a caller's trace. */
final class EventTraceScope implements AutoCloseable {
    private final String previous = MDC.get("traceId");

    EventTraceScope(String traceId) {
        if (traceId == null) {
            MDC.remove("traceId");
        } else {
            MDC.put("traceId", traceId);
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove("traceId");
        } else {
            MDC.put("traceId", previous);
        }
    }
}
