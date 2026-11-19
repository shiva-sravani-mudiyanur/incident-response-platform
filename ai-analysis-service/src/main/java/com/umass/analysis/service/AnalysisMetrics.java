package com.umass.analysis.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Bounded outcome-only metrics; telemetry must not change an analysis result. */
@Component
public class AnalysisMetrics {
    private final MeterRegistry registry;

    public AnalysisMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Export real zero baselines without inventing requests or timer observations.
        for (String outcome : new String[]{"success", "error"}) {
            try { registry.timer("analysis.duration", "outcome", outcome); }
            catch (RuntimeException ignored) { /* Recording remains best-effort after recovery. */ }
            try { registry.counter("analysis.requests", "outcome", outcome); }
            catch (RuntimeException ignored) { /* Do not fail application startup for telemetry. */ }
        }
        try { registry.counter("analysis.errors", "outcome", "error"); }
        catch (RuntimeException ignored) { /* Do not fail application startup for telemetry. */ }
    }

    public void record(long elapsedNanos, boolean success) {
        String outcome = success ? "success" : "error";
        try {
            registry.timer("analysis.duration", "outcome", outcome).record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // A registry failure must not replace the business result or original exception.
        }
        try {
            registry.counter("analysis.requests", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Keep the independent meters best-effort without logging request data.
        }
        if (!success) {
            try {
                registry.counter("analysis.errors", "outcome", "error").increment();
            } catch (RuntimeException ignored) {
                // Preserve the original business exception.
            }
        }
    }
}
