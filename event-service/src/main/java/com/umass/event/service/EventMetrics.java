package com.umass.event.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Business meters deliberately have no event-derived dimensions. */
@Component
public class EventMetrics {
    private final MeterRegistry registry;
    private final Counter received;
    private final Counter published;
    private final Counter errors;
    private final Timer success;
    private final Timer error;

    public EventMetrics(MeterRegistry registry) {
        this.registry = registry;
        received = registry.counter("event.received");
        published = registry.counter("event.published");
        errors = registry.counter("event.publish.errors");
        success = registry.timer("event.publish.duration", "outcome", "success");
        error = registry.timer("event.publish.duration", "outcome", "error");
    }

    Timer.Sample startPublish() {
        try { return Timer.start(registry); }
        catch (RuntimeException ignored) { return null; }
    }

    void published(Timer.Sample sample) {
        observe(published::increment);
        if (sample != null) observe(() -> sample.stop(success));
    }

    void failed(Timer.Sample sample) {
        observe(errors::increment);
        if (sample != null) observe(() -> sample.stop(error));
    }

    void received() {
        observe(received::increment);
    }

    private static void observe(Runnable measurement) {
        try { measurement.run(); }
        catch (RuntimeException ignored) { /* Telemetry must not alter publication results. */ }
    }
}
