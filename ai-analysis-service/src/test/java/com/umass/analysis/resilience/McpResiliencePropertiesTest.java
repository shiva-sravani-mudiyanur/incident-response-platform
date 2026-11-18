package com.umass.analysis.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.ClassUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpResiliencePropertiesTest {
    @Test void defaultsAreBoundedAndExternalOverridesBind() {
        var defaults = new Binder(new MapConfigurationPropertySource()).bindOrCreate("analysis.resilience", McpResilienceProperties.class);
        assertThat(defaults.operationTimeout()).isEqualTo(java.time.Duration.ofSeconds(20));
        assertThat(defaults.maxAttempts()).isEqualTo(2);
        assertThat(defaults.maxConcurrentCalls()).isEqualTo(4);
        var configured = new Binder(new MapConfigurationPropertySource(Map.of(
                "analysis.resilience.operation-timeout", "500ms", "analysis.resilience.max-attempts", "1")))
                .bindOrCreate("analysis.resilience", McpResilienceProperties.class);
        assertThat(configured.operationTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(configured.maxAttempts()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"operation-timeout,0ms", "operation-timeout,121s", "max-attempts,0",
            "retry-delay,-1ms", "retry-delay,2s", "sliding-window-size,1", "sliding-window-size,101",
            "minimum-number-of-calls,0", "minimum-number-of-calls,11", "failure-rate-threshold,0",
            "failure-rate-threshold,101", "failure-rate-threshold,NaN", "wait-duration-in-open-state,0ms",
            "wait-duration-in-open-state,301s", "permitted-calls-in-half-open-state,0",
            "permitted-calls-in-half-open-state,11", "max-concurrent-calls,0", "max-concurrent-calls,33"})
    void rejectsUnboundedOrInconsistentPolicy(String key, String value) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("analysis.resilience." + key, value)));
        assertThatThrownBy(() -> binder.bindOrCreate("analysis.resilience", McpResilienceProperties.class)).isInstanceOf(BindException.class);
    }

    @Test
    void rejectsUnsafeRetryCounts() throws Exception {
        String name = "com.umass.analysis.resilience.McpResilienceProperties";
        assertThat(ClassUtils.isPresent(name, getClass().getClassLoader())).as("Validated MCP resilience settings exist").isTrue();
        Class<?> type = Class.forName(name);
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("analysis.resilience.max-attempts", "4")));
        assertThatThrownBy(() -> binder.bindOrCreate("analysis.resilience", type)).isInstanceOf(BindException.class);
    }
}
