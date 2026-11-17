package com.umass.diagnostics.dto;

import java.time.Instant;
import java.util.List;

/** Processing-time window (windowStart, windowEnd]; scan counts include unselected and duplicate rows. */
public record RecentErrors(String serviceName, Instant windowStart, Instant windowEnd, String timeBasis,
                           List<ServiceEvent> events, int scannedEvents, int scannedPages,
                           boolean truncated, boolean hasMore) {
    public RecentErrors { events = List.copyOf(events); }
}
