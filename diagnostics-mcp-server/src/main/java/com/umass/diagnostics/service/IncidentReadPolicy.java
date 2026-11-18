package com.umass.diagnostics.service;

import com.umass.diagnostics.config.DiagnosticsResilienceProperties;
import com.umass.diagnostics.exception.DiagnosticException;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.stereotype.Component;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Policy is scoped to one GET, never an entire tool or history scan. */
@Component
public class IncidentReadPolicy {
    private final long operationTimeoutNanos;
    private final Retry retry;
    private final CircuitBreaker circuit;

    public IncidentReadPolicy(Resilience4JCircuitBreakerFactory factory, DiagnosticsResilienceProperties properties) {
        operationTimeoutNanos = properties.operationTimeout().toNanos();
        retry = Retry.of("incident-service-read", RetryConfig.custom()
                .maxAttempts(properties.maxAttempts()).waitDuration(properties.retryDelay())
                .retryOnException(error -> error instanceof TransientReadFailure).build());
        circuit = factory.create("incident-service-read");
    }

    public <T> T execute(Supplier<T> read) {
        if (Thread.currentThread().isInterrupted()) {
            throw new DiagnosticException("DOWNSTREAM_UNAVAILABLE", "Incident service is unavailable.");
        }
        long started = System.nanoTime();
        var cancelled = new AtomicBoolean();
        Supplier<T> guarded = () -> {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Incident read cancelled.");
            }
            if (System.nanoTime() - started >= operationTimeoutNanos) {
                throw new CompletionException(new TimeoutException("Incident read deadline exceeded."));
            }
            return read.get();
        };
        try {
            return circuit.run(Retry.decorateSupplier(retry, guarded), error -> {
                cancelled.set(true);
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof InterruptedException || cause instanceof CancellationException) {
                        Thread.currentThread().interrupt();
                    }
                    if (cause instanceof DiagnosticException diagnostic) throw diagnostic;
                }
                throw new DiagnosticException("DOWNSTREAM_UNAVAILABLE", "Incident service is unavailable.");
            });
        } finally {
            // Future cancellation cannot guarantee a blocking transport/server aborts. Never let leftovers retry.
            cancelled.set(true);
        }
    }

    static final class TransientReadFailure extends RuntimeException {
        TransientReadFailure() { super("Incident service is unavailable.", null, false, false); }
    }
}
