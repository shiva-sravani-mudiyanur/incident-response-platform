package com.umass.analysis.resilience;

import com.umass.analysis.exception.AnalysisException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Only the MCP adapter uses this policy; never wraps an analysis, model call, or write. */
@Component
public class McpReadResilience {
    public static final String CIRCUIT_ID = "diagnosticsMcpReads";
    private final org.springframework.cloud.client.circuitbreaker.CircuitBreaker breaker;
    private final Retry retry;

    public McpReadResilience(Resilience4JCircuitBreakerFactory factory, McpResilienceProperties properties) {
        breaker = factory.create(CIRCUIT_ID);
        retry = Retry.of(CIRCUIT_ID, RetryConfig.custom().maxAttempts(properties.maxAttempts())
                .waitDuration(properties.retryDelay()).retryOnException(McpReadResilience::isRetryable).build());
    }

    public <T> T read(Supplier<T> operation) {
        return execute(operation, true);
    }

    /** Initialization creates protocol state, so it is bounded but never application-retried. */
    public <T> T once(Supplier<T> operation) {
        return execute(operation, false);
    }

    private <T> T execute(Supplier<T> operation, boolean retryRead) {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        Supplier<T> guarded = () -> {
            if (!active.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            return operation.get();
        };
        try {
            return breaker.run(() -> retryRead ? retry.executeSupplier(guarded) : guarded.get(), failure -> {
                active.set(false);
                return unavailable(failure);
            });
        } finally {
            active.set(false);
        }
    }

    static boolean isRetryable(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return false;
        boolean transientIo = false;
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (ignored(failure) || failure instanceof InterruptedException || failure instanceof CancellationException) return false;
            if (failure instanceof ConnectException || failure instanceof SocketTimeoutException || failure instanceof SocketException
                    || failure instanceof HttpTimeoutException || failure instanceof TimeoutException || failure instanceof EOFException) transientIo = true;
        }
        return transientIo;
    }

    static boolean ignored(Throwable failure) {
        return failure instanceof AnalysisException || failure instanceof McpError
                || failure instanceof McpHttpClientTransportAuthorizationException
                || failure instanceof McpTransportSessionNotFoundException || failure instanceof McpTransportSessionClosedException
                || failure instanceof RejectedExecutionException || failure instanceof InterruptedException || failure instanceof CancellationException;
    }

    private static <T> T unavailable(Throwable failure) {
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof AnalysisException exception) throw exception;
        }
        throw new AnalysisException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Diagnostics MCP service is temporarily unavailable.");
    }
}
