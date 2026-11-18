package com.umass.diagnostics.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsResiliencePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DiagnosticsResilienceConfiguration.class);

    @org.junit.jupiter.api.Test
    void yamlEnvironmentOverridesBindEveryResilienceSetting() {
        runner.withInitializer(context -> {
            try {
                new org.springframework.boot.env.YamlPropertySourceLoader().load("application", new org.springframework.core.io.ClassPathResource("application.yaml"))
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }).withPropertyValues("DIAGNOSTICS_RESILIENCE_OPERATION_TIMEOUT=900ms", "DIAGNOSTICS_RESILIENCE_MAX_ATTEMPTS=3",
                "DIAGNOSTICS_RESILIENCE_RETRY_DELAY=20ms", "DIAGNOSTICS_RESILIENCE_SLIDING_WINDOW_SIZE=8",
                "DIAGNOSTICS_RESILIENCE_MINIMUM_NUMBER_OF_CALLS=3", "DIAGNOSTICS_RESILIENCE_FAILURE_RATE_THRESHOLD=75",
                "DIAGNOSTICS_RESILIENCE_WAIT_DURATION_IN_OPEN_STATE=400ms", "DIAGNOSTICS_RESILIENCE_PERMITTED_CALLS_IN_HALF_OPEN_STATE=2",
                "DIAGNOSTICS_RESILIENCE_MAX_CONCURRENT_CALLS=6").run(context -> {
            var properties = context.getBean(DiagnosticsResilienceProperties.class);
            assertThat(properties.operationTimeout()).isEqualTo(java.time.Duration.ofMillis(900));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.retryDelay()).isEqualTo(java.time.Duration.ofMillis(20));
            assertThat(properties.slidingWindowSize()).isEqualTo(8);
            assertThat(properties.minimumNumberOfCalls()).isEqualTo(3);
            assertThat(properties.failureRateThreshold()).isEqualTo(75);
            assertThat(properties.waitDurationInOpenState()).isEqualTo(java.time.Duration.ofMillis(400));
            assertThat(properties.permittedCallsInHalfOpenState()).isEqualTo(2);
            assertThat(properties.maxConcurrentCalls()).isEqualTo(6);
        });
    }

    @org.junit.jupiter.api.Test
    void configuredLimitsReachTheActualRuntimePolicy() {
        runner.withPropertyValues("diagnostics.resilience.max-concurrent-calls=6",
                "diagnostics.resilience.sliding-window-size=8", "diagnostics.resilience.minimum-number-of-calls=3",
                "diagnostics.resilience.failure-rate-threshold=75", "diagnostics.resilience.wait-duration-in-open-state=400ms",
                "diagnostics.resilience.permitted-calls-in-half-open-state=2")
                .run(context -> {
                    var factory = context.getBean(org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory.class);
                    factory.create("incident-service-read").run(() -> true);
                    var breaker = context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class)
                            .circuitBreaker("incident-service-read").getCircuitBreakerConfig();
                    assertThat(context.getBean(java.util.concurrent.ThreadPoolExecutor.class).getMaximumPoolSize()).isEqualTo(6);
                    assertThat(breaker.getSlidingWindowSize()).isEqualTo(8);
                    assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(3);
                    assertThat(breaker.getFailureRateThreshold()).isEqualTo(75);
                    assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(400L);
                    assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation-timeout=0", "operation-timeout=-1s", "operation-timeout=31s",
            "operation-timeout=1ns", "max-attempts=0", "max-attempts=4", "retry-delay=-1ms", "retry-delay=3s",
            "retry-delay=1ns", "sliding-window-size=0", "sliding-window-size=101", "minimum-number-of-calls=0",
            "minimum-number-of-calls=11", "failure-rate-threshold=0", "failure-rate-threshold=101",
            "failure-rate-threshold=NaN", "wait-duration-in-open-state=0", "wait-duration-in-open-state=6m",
            "permitted-calls-in-half-open-state=0", "permitted-calls-in-half-open-state=33",
            "max-concurrent-calls=0", "max-concurrent-calls=33"})
    void rejectsInvalidSettingsAtStartup(String setting) {
        runner.withPropertyValues("diagnostics.resilience." + setting)
                .run(context -> assertThat(context).hasFailed());
    }
}
