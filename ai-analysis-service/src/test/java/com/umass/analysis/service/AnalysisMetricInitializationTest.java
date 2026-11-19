package com.umass.analysis.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisMetricInitializationTest {
    @Test
    void knownMetersStartAtZeroWithoutRecordingAnAnalysis() {
        var registry = new SimpleMeterRegistry();
        new AnalysisMetrics(registry);
        for (String outcome : new String[]{"success", "error"}) {
            var timer = registry.find("analysis.duration").tag("outcome", outcome).timer();
            assertThat(timer).as("Analysis timer is present before the first request").isNotNull();
            assertThat(timer.count()).isZero();
            assertThat(registry.get("analysis.requests").tag("outcome", outcome).counter().count()).isZero();
        }
        assertThat(registry.get("analysis.errors").tag("outcome", "error").counter().count()).isZero();
    }
}
