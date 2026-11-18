package com.umass.diagnostics.service;

import com.umass.diagnostics.dto.IncidentDetails;
import com.umass.diagnostics.dto.ServiceHealth;
import com.umass.diagnostics.dto.ServiceEventsPage;
import com.umass.diagnostics.exception.DiagnosticException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.Set;

/** Read-only access through the configured RestClient; never accepts a URL. */
@Component
public class IncidentServiceClient {
    private final RestClient client;
    private final IncidentReadPolicy policy;
    public IncidentServiceClient(RestClient client, IncidentReadPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    public IncidentDetails getIncident(String id) {
        return policy.execute(() -> readIncident(id));
    }

    private IncidentDetails readIncident(String id) {
        var incident = get(() -> client.get().uri("/api/incidents/{id}", id), IncidentDetails.class);
        require(incident != null && id.equalsIgnoreCase(incident.id()) && canonicalUuid(incident.id())
                && incident.title() != null && !incident.title().isBlank()
                && incident.status() != null && Set.of("OPEN", "INVESTIGATING", "RESOLVED").contains(incident.status())
                && incident.severity() != null && Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(incident.severity())
                && validServiceName(incident.rootService()) && incident.createdAt() != null && incident.updatedAt() != null
                && !incident.updatedAt().isBefore(incident.createdAt())
                && incident.errorCount() != null && incident.errorCount() >= 0);
        return incident;
    }

    public ServiceHealth getServiceHealth(String name) {
        return policy.execute(() -> readServiceHealth(name));
    }

    private ServiceHealth readServiceHealth(String name) {
        var health = get(() -> client.get().uri("/api/services/{name}/health", name), ServiceHealth.class);
        require(health != null && name.equals(health.serviceName()) && health.status() != null
                && Set.of("HEALTHY", "DEGRADED", "DOWN").contains(health.status())
                && health.errorsLastMinute() != null && health.errorsLastMinute() >= 0
                && health.activeIncidentCount() != null && health.activeIncidentCount() >= 0);
        return health;
    }

    public ServiceEventsPage getServiceEvents(String name, int page, int size) {
        return policy.execute(() -> readServiceEvents(name, page, size));
    }

    private ServiceEventsPage readServiceEvents(String name, int page, int size) {
        var history = get(() -> client.get().uri("/api/services/{name}/events?page={page}&size={size}", name, page, size), ServiceEventsPage.class);
        require(history != null && name.equals(history.serviceName()) && history.events() != null
                && history.page() != null && history.page() == page && history.size() != null && history.size() == size
                && history.totalElements() != null && history.totalElements() >= 0
                && history.totalPages() != null && history.totalPages() >= 0);
        long total = history.totalElements();
        require(history.totalPages() == total / size + (total % size == 0 ? 0 : 1)
                && (page < history.totalPages() || page == 0 && total == 0)
                && history.events().size() == Math.min(size, total - (long) page * size));
        for (var event : history.events()) {
            require(event != null && canonicalUuid(event.eventId()) && name.equals(event.serviceName())
                    && event.eventType() != null && Set.of("SERVICE_STARTED", "SERVICE_STOPPED", "REQUEST_FAILED",
                            "DATABASE_ERROR", "DEPENDENCY_ERROR", "TIMEOUT").contains(event.eventType())
                    && event.severity() != null && Set.of("INFO", "WARNING", "ERROR", "CRITICAL").contains(event.severity())
                    && event.message() != null && event.timestamp() != null && event.processedAt() != null);
        }
        return history;
    }

    private <T> T get(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request, Class<T> type) {
        return attempt(request.get(), type);
    }

    private <T> T attempt(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        try {
            return request.retrieve().onStatus(status -> !status.is2xxSuccessful(), (req, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) throw new DiagnosticException("NOT_FOUND", "Requested resource was not found.");
                if (Set.of(408, 429, 500, 502, 503, 504).contains(status)) throw new IncidentReadPolicy.TransientReadFailure();
                if (status >= 500) throw unavailable();
                throw invalidResponse();
            }).body(type);
        } catch (ResourceAccessException exception) {
            checkCancellation(exception);
            throw new IncidentReadPolicy.TransientReadFailure();
        } catch (RestClientException | HttpMessageConversionException exception) {
            checkCancellation(exception);
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                // Do not treat parser/mapping IOException subclasses as transient transport failures.
                if (cause.getClass() == java.io.IOException.class || cause instanceof java.io.EOFException
                        || cause instanceof java.io.InterruptedIOException || cause instanceof java.net.SocketException
                        || cause instanceof java.net.http.HttpTimeoutException) throw new IncidentReadPolicy.TransientReadFailure();
            }
            throw invalidResponse();
        }
    }

    private static void checkCancellation(Throwable error) {
        boolean interrupted = Thread.currentThread().isInterrupted();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            interrupted |= cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof java.io.InterruptedIOException && !(cause instanceof java.net.SocketTimeoutException);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Incident read cancelled.");
        }
    }

    static boolean canonicalUuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    static boolean validServiceName(String value) {
        return value != null && !value.isBlank() && value.length() <= 100 && !value.equals(".") && !value.equals("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.chars().noneMatch(Character::isISOControl);
    }
    static void require(boolean condition) { if (!condition) throw invalidResponse(); }
    static DiagnosticException invalidResponse() {
        return new DiagnosticException("INVALID_DOWNSTREAM_RESPONSE", "Incident service returned an invalid response.");
    }
    private static DiagnosticException unavailable() {
        return new DiagnosticException("DOWNSTREAM_UNAVAILABLE", "Incident service is unavailable.");
    }
}
