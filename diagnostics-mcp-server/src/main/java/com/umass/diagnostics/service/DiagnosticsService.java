package com.umass.diagnostics.service;

import com.umass.diagnostics.config.DiagnosticsProperties;
import com.umass.diagnostics.dto.*;
import com.umass.diagnostics.exception.DiagnosticException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class DiagnosticsService {
    private final IncidentServiceClient client;
    private final DiagnosticsProperties properties;
    private final Clock clock;
    public DiagnosticsService(IncidentServiceClient client, DiagnosticsProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }
    public DiagnosticResult<IncidentDetails> getIncident(String incidentId) {
        return result(() -> { validateId(incidentId); return client.getIncident(incidentId); });
    }
    public DiagnosticResult<ServiceHealth> getServiceHealth(String serviceName) {
        return result(() -> { validateName(serviceName); return client.getServiceHealth(serviceName); });
    }
    public DiagnosticResult<ErrorFrequency> getErrorFrequency(String serviceName) {
        return result(() -> {
            validateName(serviceName);
            var health = client.getServiceHealth(serviceName);
            return new ErrorFrequency(serviceName, 60, health.errorsLastMinute(), health.errorsLastMinute() / 60.0);
        });
    }
    public DiagnosticResult<RecentErrors> getRecentErrors(String serviceName) {
        return result(() -> {
            validateName(serviceName);
            var end = clock.instant();
            var start = end.minus(properties.recentErrorWindow());
            var scan = scan(serviceName, event -> ("ERROR".equals(event.severity()) || "CRITICAL".equals(event.severity()))
                    && event.processedAt().isAfter(start) && !event.processedAt().isAfter(end));
            return new RecentErrors(serviceName, start, end, "PROCESSED_AT", scan.events(), scan.scannedEvents(),
                    scan.scannedPages(), scan.truncated(), scan.hasMore());
        });
    }

    public DiagnosticResult<RelatedEvents> getRelatedEvents(String incidentId) {
        return result(() -> {
            validateId(incidentId);
            var incident = client.getIncident(incidentId);
            final java.time.Instant start;
            final java.time.Instant end;
            try {
                start = incident.createdAt().minus(properties.relatedLookback());
                end = incident.createdAt().plus(properties.relatedLookahead());
            } catch (java.time.DateTimeException | ArithmeticException exception) {
                throw IncidentServiceClient.invalidResponse();
            }
            var scan = scan(incident.rootService(), event -> !event.processedAt().isBefore(start)
                    && !event.processedAt().isAfter(end));
            return new RelatedEvents(incidentId, incident.rootService(), "ROOT_SERVICE_TIME_WINDOW",
                    "Temporal proximity on the root service only; not proven causality or cross-service trace correlation.",
                    start, end, "PROCESSED_AT", scan.events(), scan.scannedEvents(), scan.scannedPages(),
                    scan.truncated(), scan.hasMore());
        });
    }

    private Scan scan(String name, Predicate<ServiceEvent> select) {
        var selected = new ArrayList<ServiceEvent>();
        var ids = new HashSet<UUID>();
        int scannedEvents = 0;
        int scannedPages = 0;
        boolean hasMore = true;
        boolean changed = false;
        Long firstTotal = null;
        for (int page = 0; page < properties.maxHistoryPages() && hasMore; page++) {
            var history = client.getServiceEvents(name, page, properties.historyPageSize());
            if (firstTotal == null) firstTotal = history.totalElements();
            else if (!firstTotal.equals(history.totalElements())) changed = true;
            scannedPages++;
            scannedEvents += history.events().size();
            for (var event : history.events()) {
                if (select.test(event) && ids.add(UUID.fromString(event.eventId()))) selected.add(event);
            }
            // History is sorted by SOURCE timestamp, never stop on old processedAt values.
            hasMore = page + 1 < history.totalPages();
        }
        return new Scan(selected, scannedEvents, scannedPages, changed || hasMore, hasMore);
    }
    private record Scan(List<ServiceEvent> events, int scannedEvents, int scannedPages, boolean truncated, boolean hasMore) { }

    private static void validateId(String id) {
        if (!IncidentServiceClient.canonicalUuid(id)) throw invalidInput();
        UUID.fromString(id);
    }
    private static void validateName(String name) {
        if (!IncidentServiceClient.validServiceName(name)) throw invalidInput();
    }
    private static DiagnosticException invalidInput() {
        return new DiagnosticException("INVALID_INPUT", "Provide a canonical incident UUID or a valid service name.");
    }
    private static <T> DiagnosticResult<T> result(Supplier<T> action) {
        try { return DiagnosticResult.ok(action.get()); }
        catch (DiagnosticException exception) { return DiagnosticResult.failure(exception.code(), exception.getMessage()); }
    }
}
