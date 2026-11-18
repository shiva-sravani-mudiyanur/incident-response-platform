package com.umass.diagnostics.config;

import com.umass.diagnostics.exception.DiagnosticException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(DiagnosticsResilienceProperties.class)
public class DiagnosticsResilienceConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor incidentReadExecutor(DiagnosticsResilienceProperties properties) {
        return new ThreadPoolExecutor(properties.maxConcurrentCalls(), properties.maxConcurrentCalls(), 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "incident-service-read");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    CircuitBreakerRegistry incidentReadCircuitBreakerRegistry() { return CircuitBreakerRegistry.ofDefaults(); }

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> incidentReadCustomizer(ThreadPoolExecutor incidentReadExecutor,
            DiagnosticsResilienceProperties properties) {
        return factory -> {
            factory.configureExecutorService(incidentReadExecutor);
            factory.configureDefault(id -> new org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(CircuitBreakerConfig.custom().slidingWindowSize(properties.slidingWindowSize()).minimumNumberOfCalls(properties.minimumNumberOfCalls())
                            .failureRateThreshold(properties.failureRateThreshold()).waitDurationInOpenState(properties.waitDurationInOpenState())
                            .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                            .ignoreException(error -> error instanceof java.util.concurrent.CancellationException
                                    || error instanceof java.util.concurrent.RejectedExecutionException
                                    || error instanceof InterruptedException
                                    || error instanceof DiagnosticException diagnostic
                                    && !"DOWNSTREAM_UNAVAILABLE".equals(diagnostic.code()))
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(properties.operationTimeout())
                            .cancelRunningFuture(true).build()).build());
        };
    }

    @Bean
    Resilience4JCircuitBreakerFactory incidentReadCircuitBreakerFactory(CircuitBreakerRegistry registry,
            Customizer<Resilience4JCircuitBreakerFactory> incidentReadCustomizer) {
        // A dedicated factory keeps the mandatory deadline/thread pool enabled and avoids a second bulkhead.
        var factory = new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(), null);
        incidentReadCustomizer.customize(factory);
        return factory;
    }
}
