package com.umass.diagnostics.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticsPropertiesTest {
    @Test
    void acceptsBoundedConfigurationForAnIncidentServiceOrigin() {
        assertThat(properties("http://localhost:8080", 100, 5).historyPageSize()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "/relative", "ftp://host", "http://user:password@host",
            "http://host/api/incidents", "http://host?secret=value", "http://host#fragment", "http://host:0"})
    void rejectsInvalidOrAmbiguousDownstreamOrigins(String origin) {
        assertThatThrownBy(() -> properties(origin, 100, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedHistoryScans() {
        assertThatThrownBy(() -> properties("http://localhost:8080", 101, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://localhost:8080", 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://localhost:8080", 100, 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInfiniteTimeoutsAndNegativeWindows() {
        assertThatThrownBy(() -> new DiagnosticsProperties(URI.create("http://localhost:8080"), Duration.ZERO,
                Duration.ofSeconds(5), 100, 5, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiagnosticsProperties(URI.create("http://localhost:8080"), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 100, 5, Duration.ofSeconds(-1), Duration.ofSeconds(60), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DiagnosticsProperties properties(String origin, int pageSize, int pages) {
        return new DiagnosticsProperties(URI.create(origin), Duration.ofSeconds(2), Duration.ofSeconds(5),
                pageSize, pages, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60));
    }
}
