package com.umass.diagnostics.dto;

import java.time.Instant;
import java.util.List;

/** Inclusive processing-time window around incident creation; no causal or cross-service inference. */
public record RelatedEvents(String incidentId, String rootService, String relationshipType, String explanation,
                            Instant windowStart, Instant windowEnd, String timeBasis, List<ServiceEvent> events,
                            int scannedEvents, int scannedPages, boolean truncated, boolean hasMore) {
    public RelatedEvents { events = List.copyOf(events); }
}
