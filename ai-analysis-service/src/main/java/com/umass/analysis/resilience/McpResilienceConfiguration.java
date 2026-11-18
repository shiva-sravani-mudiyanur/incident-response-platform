package com.umass.analysis.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpResilienceProperties.class)
public class McpResilienceConfiguration {
    @Bean
    public CircuitBreakerRegistry mcpReadCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    public Resilience4JCircuitBreakerFactory mcpReadCircuitBreakerFactory(CircuitBreakerRegistry registry,
            @org.springframework.beans.factory.annotation.Qualifier("mcpCircuitCustomizer")
            Customizer<Resilience4JCircuitBreakerFactory> customizer) {
        // Only the validated boundary settings may control its mandatory deadline and executor.
        var factory = new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(), null);
        customizer.customize(factory);
        return factory;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService mcpReadExecutor(McpResilienceProperties properties) {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(properties.maxConcurrentCalls(), properties.maxConcurrentCalls(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
                    var thread = new Thread(task, "mcp-diagnostic-read-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> mcpCircuitCustomizer(McpResilienceProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("mcpReadExecutor") ExecutorService executor) {
        return factory -> {
            factory.configureExecutorService(executor);
            factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(CircuitBreakerConfig.custom().slidingWindowSize(properties.slidingWindowSize())
                            .minimumNumberOfCalls(properties.minimumNumberOfCalls()).failureRateThreshold(properties.failureRateThreshold())
                            .waitDurationInOpenState(properties.waitDurationInOpenState())
                            .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                            .ignoreException(McpReadResilience::ignored).build())
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(properties.operationTimeout())
                            .cancelRunningFuture(true).build()).build());
        };
    }
}
