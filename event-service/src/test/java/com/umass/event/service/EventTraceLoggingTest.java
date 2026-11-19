package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.model.EventSeverity;
import com.umass.event.model.EventType;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EventTraceLoggingTest {
    @Test
    void receiptScopesExactTraceAndRestoresCallerContext() {
        var publisher = mock(KafkaEventPublisher.class);
        var service = new EventService(publisher, new EventMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        MDC.put("traceId", "caller");
        MDC.put("other", "keep");
        try {
            doAnswer(invocation -> {
                assertThat(MDC.get("traceId")).isEqualTo("human\ntrace");
                assertThat(MDC.get("other")).isEqualTo("keep");
                return null;
            }).when(publisher).publish(any());
            service.accept(event("human\ntrace"));
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("traceId", "caller", "other", "keep"));
        } finally {
            MDC.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void receiptAndPublishResultAreSafeStructuredJsonWithExactTrace(boolean failure) {
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, ServiceEvent> template = mock(
                org.springframework.kafka.core.KafkaTemplate.class);
        var metrics = new EventMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var publisher = new KafkaEventPublisher(template, "service-events", java.time.Duration.ofSeconds(1), metrics);
        var service = new EventService(publisher, metrics);
        var event = event("human\r\ntrace\"value");
        when(template.send(org.mockito.ArgumentMatchers.eq("service-events"), any(), any())).thenAnswer(invocation -> {
            if (failure) throw new IllegalStateException("secret-exception credentials=password");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.umass.event.service");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent entry) {
                entry.prepareForDeferredProcessing();
                super.append(entry);
            }
        };
        appender.start();
        logger.addAppender(appender);
        var encoderContext = new ch.qos.logback.classic.LoggerContext();
        encoderContext.putObject(org.springframework.core.env.Environment.class.getName(),
                new org.springframework.mock.env.MockEnvironment());
        var encoder = new org.springframework.boot.logging.logback.StructuredLogEncoder();
        encoder.setContext(encoderContext);
        encoder.setFormat("logstash");
        encoder.start();
        MDC.put("traceId", "caller");
        try {
            if (failure) org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.accept(event))
                    .isInstanceOf(com.umass.event.exception.EventPublishException.class);
            else service.accept(event);
            assertThat(MDC.get("traceId")).isEqualTo("caller");
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .containsExactly("Received event", failure ? "Event publication failed" : "Published event");
            for (var entry : appender.list) {
                assertThat(entry.getMDCPropertyMap()).containsEntry("traceId", event.traceId());
                assertThat(entry.getThrowableProxy()).isNull();
                String json = new String(encoder.encode(entry), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(json.lines().count()).isEqualTo(1);
                assertThat(json).doesNotContain("secret-message", "secret-metadata", "secret-exception", "credentials", "password");
                var parsed = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
                assertThat(parsed.get("traceId").asString()).isEqualTo(event.traceId());
                assertThat(parsed.get("eventId").asString()).isNotBlank();
                assertThat(parsed.get("serviceName").asString()).isEqualTo(event.serviceName());
            }
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
            encoderContext.stop();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nestedReceiptAndDirectPublishRestoreOuterTraceEvenWhenInnerTraceIsNull(boolean failure) {
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, ServiceEvent> template = mock(
                org.springframework.kafka.core.KafkaTemplate.class);
        var metrics = new EventMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var publisher = new KafkaEventPublisher(template, "service-events", java.time.Duration.ofSeconds(1), metrics);
        var service = new EventService(publisher, metrics);
        var outer = event("outer");
        when(template.send(org.mockito.ArgumentMatchers.eq("service-events"), any(), any())).thenAnswer(invocation -> {
            ServiceEvent current = invocation.getArgument(2);
            assertThat(MDC.get("traceId")).isEqualTo(current.traceId());
            if (current.traceId() == null) {
                if (failure) throw new IllegalStateException("secret-error");
            } else {
                if (failure) org.assertj.core.api.Assertions.assertThatThrownBy(() -> publisher.publish(event(null)))
                        .isInstanceOf(com.umass.event.exception.EventPublishException.class);
                else publisher.publish(event(null));
                assertThat(MDC.get("traceId")).isEqualTo("outer");
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        MDC.put("traceId", "caller");
        MDC.put("other", "keep");
        try {
            service.accept(outer);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("traceId", "caller", "other", "keep"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void reusedRequestThreadDoesNotLeakTraceAcrossSuccessFailureOrMissingTrace() throws Exception {
        var publisher = mock(KafkaEventPublisher.class);
        var service = new EventService(publisher, new EventMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        doAnswer(invocation -> {
            ServiceEvent current = invocation.getArgument(0);
            assertThat(MDC.get("traceId")).isEqualTo(current.traceId());
            if ("failure".equals(current.traceId())) throw new com.umass.event.exception.EventPublishException(
                    new IllegalStateException("secret-error"));
            return null;
        }).when(publisher).publish(any());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            for (String trace : new String[]{"first", "failure", null, "last"}) {
                executor.submit(() -> {
                    assertThat(MDC.get("traceId")).isNull();
                    if ("failure".equals(trace)) org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.accept(event(trace)))
                            .isInstanceOf(com.umass.event.exception.EventPublishException.class);
                    else service.accept(event(trace));
                    assertThat(MDC.get("traceId")).isNull();
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    static ServiceEvent event(String trace) {
        return new ServiceEvent(null, "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "secret-message", Instant.parse("2026-11-15T12:00:00Z"),
                trace, Map.of("secret", "secret-metadata"));
    }
}
