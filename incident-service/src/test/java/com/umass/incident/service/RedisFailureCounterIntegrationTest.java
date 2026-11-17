package com.umass.incident.service;

import com.umass.incident.config.CorrelationProperties;
import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.EventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisFailureCounterIntegrationTest {
    private GenericContainer<?> redis;
    private LettuceConnectionFactory connection;
    private StringRedisTemplate template;
    private RedisFailureCounter counter;
    private String serviceName;

    @BeforeAll
    void startRedis() {
        redis = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);
        redis.start();
        connection = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build());
        connection.afterPropertiesSet();
        template = new StringRedisTemplate(connection);
    }

    @BeforeEach
    void setUp() {
        counter = new RedisFailureCounter(template, new CorrelationProperties(5, 2));
        serviceName = "counter-" + UUID.randomUUID();
    }

    @AfterAll
    void stopRedis() {
        try {
            if (connection != null) connection.destroy();
        } finally {
            if (redis != null) redis.stop();
        }
    }

    @Test
    void countsDistinctErrorsAndCriticalEventsTogether() {
        for (int i = 0; i < 4; i++) counter.record(event(serviceName, EventSeverity.ERROR));

        var snapshot = counter.record(event(serviceName, EventSeverity.CRITICAL));

        assertThat(snapshot.count()).isEqualTo(5);
        assertThat(snapshot.hasCritical()).isTrue();
    }

    @Test
    void redeliveryOfSameEventDoesNotInflateWindow() {
        var event = event(serviceName, EventSeverity.ERROR);
        counter.record(event);

        assertThat(counter.record(event).count()).isEqualTo(1);
    }

    @Test
    void servicesHaveIndependentRedisKeys() {
        counter.record(event(serviceName, EventSeverity.ERROR));
        counter.record(event(serviceName, EventSeverity.ERROR));

        assertThat(counter.record(event("other-" + serviceName, EventSeverity.ERROR)).count()).isEqualTo(1);
        assertThat(template.opsForZSet().zCard(key())).isEqualTo(2);
    }

    @Test
    void bothKeysExpireAutomaticallyAfterConfiguredWindow() throws Exception {
        counter.record(event(serviceName, EventSeverity.CRITICAL));
        assertThat(template.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        assertThat(template.getExpire(key() + ":critical", TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(template.hasKey(key())) && System.nanoTime() < deadline) Thread.sleep(50);

        assertThat(template.hasKey(key())).isFalse();
        assertThat(template.hasKey(key() + ":critical")).isFalse();
        assertThat(new RedisRecentErrors(template).countLastMinute(serviceName)).isEqualTo(1);
        assertThat(counter.record(event(serviceName, EventSeverity.ERROR)).count()).isEqualTo(1);
    }

    @Test
    void oldFailuresAndCriticalSeverityLeaveTheSlidingWindow() {
        var old = event(serviceName, EventSeverity.CRITICAL);
        counter.record(old);
        double expiredScore = template.opsForZSet().score(key(), old.eventId().toString()) - 3000;
        template.opsForZSet().add(key(), old.eventId().toString(), expiredScore);
        template.opsForZSet().add(key() + ":critical", old.eventId().toString(), expiredScore);

        var snapshot = counter.record(event(serviceName, EventSeverity.ERROR));

        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.hasCritical()).isFalse();
    }

    @Test
    void simultaneousUpdatesAreAtomic() throws Exception {
        // A longer window isolates atomicity from the short expiry tested above.
        counter = new RedisFailureCounter(template, new CorrelationProperties(5, 60));
        var futures = new ArrayList<Future<RedisFailureCounter.WindowSnapshot>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 40; i++) futures.add(executor.submit(() -> counter.record(event(serviceName, EventSeverity.ERROR))));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }

        assertThat(template.opsForZSet().zCard(key())).isEqualTo(40);
    }

    @Test
    void healthMinuteCountsDistinctFailuresIndependentlyOfCorrelationWindow() {
        var reader = new RedisRecentErrors(template);
        var failure = event(serviceName, EventSeverity.ERROR);
        counter.record(failure);
        counter.record(failure);

        assertThat(reader.countLastMinute(serviceName)).isEqualTo(1);
        assertThat(reader.countLastMinute("unknown-" + serviceName)).isZero();
        assertThat(template.getExpire("health-errors:{" + serviceName + "}", TimeUnit.MILLISECONDS))
                .isBetween(5000L, 60000L);
    }

    @Test
    void healthReadsExcludeOldScoresWithoutNewEventsOrRefreshingExpiry() {
        var reader = new RedisRecentErrors(template);
        var failure = event(serviceName, EventSeverity.CRITICAL);
        counter.record(failure);
        String healthKey = "health-errors:{" + serviceName + "}";
        double score = template.opsForZSet().score(healthKey, failure.eventId().toString());
        template.opsForZSet().add(healthKey, failure.eventId().toString(), score - 61000);
        long before = template.getExpire(healthKey, TimeUnit.MILLISECONDS);

        assertThat(reader.countLastMinute(serviceName)).isZero();
        assertThat(template.opsForZSet().zCard(healthKey)).isEqualTo(1);
        assertThat(template.getExpire(healthKey, TimeUnit.MILLISECONDS)).isBetween(1L, before);
    }

    @Test
    void healthMinuteDoesNotCountFutureScores() {
        var failure = event(serviceName, EventSeverity.ERROR);
        counter.record(failure);
        String healthKey = "health-errors:{" + serviceName + "}";
        double score = template.opsForZSet().score(healthKey, failure.eventId().toString());
        template.opsForZSet().add(healthKey, failure.eventId().toString(), score + 61000);

        assertThat(new RedisRecentErrors(template).countLastMinute(serviceName)).isZero();
    }

    private String key() {
        return "errors:{" + serviceName + "}";
    }

    private ServiceEvent event(String name, EventSeverity severity) {
        return new ServiceEvent(UUID.randomUUID(), name, EventType.REQUEST_FAILED, severity,
                "Request failed", Instant.parse("2026-11-16T12:00:00Z"), null, Map.of());
    }
}
