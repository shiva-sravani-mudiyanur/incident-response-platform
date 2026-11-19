package com.umass.incident.service;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import com.umass.incident.observability.IncidentMetrics;
import com.umass.incident.repository.IncidentEventRepository;
import com.umass.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction interceptor/completion, no infrastructure or synthetic commit callbacks. */
class EventProcessingTransactionMetricsTest {
    @Test
    void transactionCompletionIncludesCommitTimeAndFailureIsNeverReportedAsSuccess() {
        for (boolean failCommit : new boolean[]{false, true}) {
            var meterClock = new MockClock();
            var registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, meterClock);
            var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
            var events = mock(IncidentEventRepository.class);
            var correlation = mock(CorrelationService.class);
            when(events.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            var transactionManager = new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) {
                    assertThat(registry.get("incident.events.processed").counter().count()).isZero();
                    meterClock.add(Duration.ofSeconds(2));
                    if (failCommit) throw new TransactionSystemException("commit unavailable");
                }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
            try (var validators = Validation.buildDefaultValidatorFactory()) {
                var target = new EventProcessingService(events, validators.getValidator(), Clock.systemUTC(), correlation, metrics);
                var proxy = new ProxyFactory(target);
                proxy.setProxyTargetClass(true);
                var interceptor = new TransactionInterceptor();
                interceptor.setTransactionManager(transactionManager);
                interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                proxy.addAdvice(interceptor);
                var service = (EventProcessingService) proxy.getProxy();
                var event = new ServiceEvent(UUID.randomUUID(), "service", EventType.REQUEST_FAILED, EventSeverity.ERROR,
                        "private payload", Instant.now(), "trace-transaction", Map.of());
                if (failCommit) assertThatThrownBy(() -> service.process(event)).isInstanceOf(TransactionSystemException.class);
                else service.process(event);
                assertThat(registry.get("incident.events.processed").counter().count()).isEqualTo(failCommit ? 0 : 1);
                assertThat(registry.get("incident.events.errors").counter().count()).isEqualTo(failCommit ? 1 : 0);
                assertThat(registry.get("incident.events.processing").tag("outcome", failCommit ? "error" : "success")
                        .timer().totalTime(TimeUnit.SECONDS)).isEqualTo(2);
                assertThat(org.slf4j.MDC.get("traceId")).isNull();
            }
        }
    }
}
