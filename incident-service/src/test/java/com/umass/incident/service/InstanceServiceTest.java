package com.umass.incident.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceServiceTest {
    @Test
    void returnsServiceHostAndInjectedUtcTime() {
        var now = Instant.parse("2026-11-18T12:00:00Z");
        var service = new InstanceService(Clock.fixed(now, ZoneOffset.UTC), "incident-service", "incident-service-pod-a");
        var response = service.instance();
        assertThat(response.serviceName()).isEqualTo("incident-service");
        assertThat(response.hostname()).isEqualTo("incident-service-pod-a");
        assertThat(response.timestamp()).isEqualTo(now);
    }

    @Test
    void capturesANewTimestampWithoutChangingTheInstanceIdentity() {
        var now = Instant.parse("2026-11-18T12:00:00Z");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, now.plusSeconds(1));
        var service = new InstanceService(clock, "incident-service", "incident-service-pod-a");
        var first = service.instance();
        var second = service.instance();
        assertThat(first.timestamp()).isEqualTo(now);
        assertThat(second.timestamp()).isEqualTo(now.plusSeconds(1));
        assertThat(second.hostname()).isEqualTo(first.hostname());
    }
}
