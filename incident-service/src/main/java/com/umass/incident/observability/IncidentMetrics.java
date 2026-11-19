package com.umass.incident.observability;

import com.umass.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class IncidentMetrics {
    private final MeterRegistry registry;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter errors;
    public IncidentMetrics(MeterRegistry registry, IncidentRepository repository) {
        this.registry = registry;
        // Global DB snapshot on every replica: select/max, NEVER sum across replicas.
        io.micrometer.core.instrument.Gauge.builder("incident.incidents.active", repository, repo -> {
            try {
                return repo.countByStatusIn(java.util.List.of(
                        com.umass.incident.model.IncidentStatus.OPEN,
                        com.umass.incident.model.IncidentStatus.INVESTIGATING));
            } catch (RuntimeException ignored) {
                return Double.NaN;
            }
        }).description("Global DB OPEN + INVESTIGATING count; do not sum replicas").register(registry);
        processed = registry.counter("incident.events.processed");
        duplicates = registry.counter("incident.events.duplicates");
        errors = registry.counter("incident.events.errors");
        for (String source : new String[]{"manual", "correlation"})
            registry.counter("incident.incidents.created", "source", source);
        for (String outcome : new String[]{"success", "duplicate", "error"})
            registry.timer("incident.events.processing", "outcome", outcome);
    }
    public Attempt begin() { return new Attempt(); }
    public void created(String source) {
        if (!source.equals("manual") && !source.equals("correlation")) throw new IllegalArgumentException("Unknown source");
        if (transactional()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    observe(() -> registry.counter("incident.incidents.created", "source", source).increment());
                }
            });
        }
    }
    private static boolean transactional() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
    }
    private static void observe(Runnable measurement) {
        try { measurement.run(); }
        catch (RuntimeException ignored) { /* Never fail a business operation or commit callback for telemetry. */ }
    }
    private Timer.Sample startTimer() {
        try { return Timer.start(registry); }
        catch (RuntimeException ignored) { return null; }
    }
    public class Attempt {
        private final Timer.Sample sample = startTimer();
        private String outcome = "error";
        private boolean completed;
        private final boolean deferred = transactional();
        private Attempt() {
            if (deferred) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    complete(status == STATUS_COMMITTED ? outcome : "error");
                }
            });
        }
        public void success() { finish("success"); }
        public void duplicate() { observe(duplicates::increment); finish("duplicate"); }
        public void error() { finish("error"); }
        private void finish(String result) { outcome = result; if (!deferred) complete(result); }
        private void complete(String result) {
            if (completed) return;
            completed = true;
            switch (result) {
                case "success" -> { if (deferred) observe(processed::increment); }
                case "duplicate" -> { /* Delivery counted on detection, independent of commit. */ }
                default -> observe(errors::increment);
            }
            if (sample != null) observe(() -> sample.stop(registry.timer("incident.events.processing", "outcome", result)));
        }
    }
}
