package com.umass.incident.service;

import com.umass.incident.observability.IncidentMetrics;
import com.umass.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IncidentMetricsTest {
    @Test
    void failingTelemetryDoesNotEscapeProcessingOrCommitCallbacks() {
        var registry = org.mockito.Mockito.spy(new SimpleMeterRegistry());
        var broken = mock(io.micrometer.core.instrument.Counter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("telemetry unavailable")).when(broken).increment();
        for (String name : new String[]{"incident.events.processed", "incident.events.duplicates", "incident.events.errors"})
            org.mockito.Mockito.doReturn(broken).when(registry).counter(name);
        var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.mockito.Mockito.doThrow(new IllegalStateException("registry unavailable")).when(registry)
                    .counter("incident.incidents.created", "source", "manual");
            org.assertj.core.api.Assertions.assertThatCode(() -> {
                metrics.begin().success();
                metrics.begin().duplicate();
                metrics.begin().error();
                metrics.created("manual");
                var callbacks = TransactionSynchronizationManager.getSynchronizations();
                callbacks.forEach(TransactionSynchronization::afterCommit);
                callbacks.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            }).doesNotThrowAnyException();
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test
    void duplicateDeliveryIsObservedEvenIfSurroundingTransactionLaterRollsBack() {
        var registry = new SimpleMeterRegistry();
        var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.begin().duplicate();
            assertThat(registry.get("incident.events.duplicates").counter().count()).isEqualTo(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(registry.get("incident.events.duplicates").counter().count()).isEqualTo(1);
            assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
            assertThat(registry.get("incident.events.processing").tag("outcome", "error").timer().count()).isEqualTo(1);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test
    void unproxiedCallCannotClaimACommittedEventOrIncident() {
        var registry = new SimpleMeterRegistry();
        var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
        metrics.begin().success();
        metrics.created("manual");
        assertThat(registry.get("incident.events.processed").counter().count()).isZero();
        assertThat(registry.get("incident.incidents.created").tag("source", "manual").counter().count()).isZero();
    }

    @Test
    void activeGaugeReadsCurrentGlobalDatabaseCountAndReportsNaNOnFailure() {
        var count = new java.util.concurrent.atomic.AtomicLong(4);
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var repository = mock(IncidentRepository.class, invocation -> {
            if (invocation.getMethod().getName().equals("countByStatusIn")) {
                assertThat((java.util.List<?>) invocation.getArgument(0)).isEqualTo(java.util.List.of(
                        com.umass.incident.model.IncidentStatus.OPEN,
                        com.umass.incident.model.IncidentStatus.INVESTIGATING));
                if (fail.get()) throw new IllegalStateException("not logged");
                return count.get();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        var registry = new SimpleMeterRegistry();
        new IncidentMetrics(registry, repository);
        var gauge = registry.find("incident.incidents.active").gauge();
        assertThat(gauge).as("Global DB-derived active gauge").isNotNull();
        assertThat(gauge.value()).isEqualTo(4);
        count.set(2);
        assertThat(gauge.value()).isEqualTo(2);
        fail.set(true);
        assertThat(gauge.value()).isNaN();
    }

    @Test
    void rollbackAndUnknownCommitNeverCountSuccessAndEachAttemptCountsOneError() {
        for (int status : new int[]{TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN}) {
            var registry = new SimpleMeterRegistry();
            var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                var attempt = metrics.begin();
                attempt.success();
                metrics.created("manual");
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
                assertThat(registry.get("incident.events.processed").counter().count()).isZero();
                assertThat(registry.get("incident.incidents.created").tag("source", "manual").counter().count()).isZero();
                assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
                assertThat(registry.get("incident.events.processing").tag("outcome", "error").timer().count()).isEqualTo(1);
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test
    void duplicateAndFailedAttemptsHaveExclusiveFixedOutcomes() {
        var registry = new SimpleMeterRegistry();
        var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
        metrics.begin().duplicate();
        metrics.begin().error();
        assertThat(registry.get("incident.events.duplicates").counter().count()).isEqualTo(1);
        assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("incident.events.processed").counter().count()).isZero();
        for (String outcome : new String[]{"duplicate", "error"})
            assertThat(registry.get("incident.events.processing").tag("outcome", outcome).timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags())
                .allSatisfy(t -> assertThat(t.getKey()).isIn("source", "outcome")));
    }

    @Test
    void newEventAndCreateOnlyCountAfterCommitAndTimerWaitsForCompletion() {
        var registry = new SimpleMeterRegistry();
        var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var attempt = metrics.begin();
            attempt.success();
            metrics.created("correlation");
            assertThat(registry.get("incident.events.processed").counter().count()).isZero();
            assertThat(registry.get("incident.events.processing").tag("outcome", "success").timer().count()).isZero();
            assertThat(registry.get("incident.incidents.created").tag("source", "correlation").counter().count()).isZero();
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(TransactionSynchronization::afterCommit);
            callbacks.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(registry.get("incident.events.processed").counter().count()).isEqualTo(1);
            assertThat(registry.get("incident.events.processing").tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(registry.get("incident.incidents.created").tag("source", "correlation").counter().count()).isEqualTo(1);
        } finally { TransactionSynchronizationManager.clear(); }
    }
}
