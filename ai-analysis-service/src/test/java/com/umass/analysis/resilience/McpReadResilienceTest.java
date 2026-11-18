package com.umass.analysis.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;

import com.umass.analysis.exception.AnalysisException;
import io.modelcontextprotocol.spec.McpError;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import java.net.ConnectException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class McpReadResilienceTest {
    private ExecutorService executor;
    private Resilience4JCircuitBreakerFactory factory;

    @AfterEach void closeExecutor() { if (executor != null) executor.shutdownNow(); }

    @Test
    void retriesOnlyTheSafeReadAndPreservesItsResult() throws Exception {
        var resilience = policy(Map.of("retry-delay", "0ms"));
        var attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            if (attempts.incrementAndGet() == 1) throw new CompletionException(new ConnectException("private endpoint"));
            return "observed result";
        };
        assertThat(resilience.read(read)).isEqualTo("observed result");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void protocolErrorsAreNotRetriedAndDoNotPoisonCircuit() {
        var resilience = policy(Map.of("sliding-window-size", "2", "minimum-number-of-calls", "2"));
        var attempts = new AtomicInteger();
        for (int call = 0; call < 3; call++) {
            assertThatThrownBy(() -> resilience.read(() -> {
                attempts.incrementAndGet();
                throw new CompletionException(mock(McpError.class));
            })).isInstanceOfSatisfying(AnalysisException.class, error -> {
                assertThat(error.status().value()).isEqualTo(503);
                assertThat(error.getCause()).isNull();
            });
        }
        assertThat(factory.getCircuitBreakerRegistry().circuitBreaker(McpReadResilience.CIRCUIT_ID).getState())
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(attempts).hasValue(3);
    }

    @Test void retriesAreLimitedAndInitializationIsNeverReplayed() {
        var resilience = policy(Map.of("retry-delay", "0ms"));
        var attempts = new AtomicInteger();
        Supplier<String> failure = () -> {
            attempts.incrementAndGet();
            throw new CompletionException(new ConnectException("private connection details"));
        };
        assertThatThrownBy(() -> resilience.read(failure)).isInstanceOfSatisfying(AnalysisException.class,
                error -> assertThat(error.getMessage()).doesNotContain("private", "connection details"));
        assertThat(attempts).hasValue(2);
        attempts.set(0);
        assertThatThrownBy(() -> resilience.once(failure)).isInstanceOf(AnalysisException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test void unknownSdkErrorsAreNotRetriedByParsingTheirMessages() {
        var resilience = policy(Map.of());
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> resilience.read(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("HTTP 503 private upstream body");
        })).isInstanceOfSatisfying(AnalysisException.class, error -> {
            assertThat(error.code()).isEqualTo("DIAGNOSTICS_UNAVAILABLE");
            assertThat(error.getMessage()).doesNotContain("private", "HTTP 503");
        });
        assertThat(attempts).hasValue(1);
    }

    @Test void circuitOpensAfterLogicalFailuresAndHalfOpenSuccessRecovers() {
        var resilience = policy(Map.of("sliding-window-size", "2", "minimum-number-of-calls", "2", "retry-delay", "0ms"));
        var attempts = new AtomicInteger();
        Supplier<String> failure = () -> {
            attempts.incrementAndGet();
            throw new CompletionException(new ConnectException("private"));
        };
        for (int call = 0; call < 3; call++) assertThatThrownBy(() -> resilience.read(failure)).isInstanceOf(AnalysisException.class);
        assertThat(attempts).hasValue(4);
        var circuit = factory.getCircuitBreakerRegistry().circuitBreaker(McpReadResilience.CIRCUIT_ID);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(circuit.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        circuit.transitionToHalfOpenState();
        assertThat(resilience.read(() -> "fresh observation")).isEqualTo("fresh observation");
        assertThat(circuit.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
    }

    @Test void totalDeadlineCoversRetryDelayAndCancelsFurtherAttempts() throws Exception {
        var resilience = policy(Map.of("operation-timeout", "100ms", "retry-delay", "1s"));
        var attempts = new AtomicInteger();
        long started = System.nanoTime();
        assertThatThrownBy(() -> resilience.read(() -> {
            attempts.incrementAndGet();
            throw new CompletionException(new ConnectException("private"));
        })).isInstanceOf(AnalysisException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts).hasValue(1);
    }

    @Test void saturatedExecutorFailsFastInsteadOfQueuingMoreWork() throws Exception {
        var resilience = policy(Map.of("operation-timeout", "100ms", "max-concurrent-calls", "1"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejectedWork = new AtomicInteger();
        try {
            assertThatThrownBy(() -> resilience.once(() -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* Simulate noncooperative upstream work. */ }
                }
                return "too late";
            })).isInstanceOf(AnalysisException.class);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> resilience.read(() -> rejectedWork.incrementAndGet())).isInstanceOf(AnalysisException.class);
            assertThat(rejectedWork).hasValue(0);
            assertThat(((java.util.concurrent.ThreadPoolExecutor) executor).getQueue()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void interruptedCallerDoesNotLeaveAReadRetryRunning() throws Exception {
        var resilience = policy(Map.of("retry-delay", "0ms"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
        var caller = new Thread(() -> {
            try {
                resilience.read(() -> {
                    if (attempts.incrementAndGet() == 1) {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        throw new CompletionException(new ConnectException("private"));
                    }
                    return "must not retry after caller exits";
                });
            } catch (Throwable error) {
                failure.set(error);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(AnalysisException.class);
            assertThat(interruptRestored).isTrue();
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts).hasValue(1);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(2000);
        }
    }

    private McpReadResilience policy(Map<String, String> overrides) {
        var values = new java.util.HashMap<String, Object>();
        overrides.forEach((key, value) -> values.put("analysis.resilience." + key, value));
        var properties = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values))
                .bindOrCreate("analysis.resilience", McpResilienceProperties.class);
        var configuration = new McpResilienceConfiguration();
        executor = configuration.mcpReadExecutor(properties);
        factory = new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), null);
        configuration.mcpCircuitCustomizer(properties, executor).customize(factory);
        return new McpReadResilience(factory, properties);
    }
}
