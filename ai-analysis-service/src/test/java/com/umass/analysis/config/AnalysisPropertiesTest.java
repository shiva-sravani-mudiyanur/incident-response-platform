package com.umass.analysis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisPropertiesTest {
    @Test
    void acceptsBoundedReadOnlyAnalysisConfiguration() {
        assertThat(properties("http://127.0.0.1:8084", 3, 8).maxModelRounds()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/mcp", "http://user:secret@host", "http://host/mcp", "http://host?q=1",
            "http://host#fragment", "http://host:0", "/relative"})
    void rejectsNonOriginOrCredentialBearingMcpAddresses(String value) {
        assertThatThrownBy(() -> properties(value, 3, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedAgentLoops() {
        assertThatThrownBy(() -> properties("http://localhost:8084", 0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://localhost:8084", 6, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("http://localhost:8084", 3, 21)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInfiniteMcpTimeout() {
        assertThatThrownBy(() -> new AnalysisProperties(URI.create("http://localhost:8084"), Duration.ZERO,
                Duration.ofSeconds(15), 3, 8, 64000, 16000)).isInstanceOf(IllegalArgumentException.class);
    }

    private AnalysisProperties properties(String uri, int rounds, int calls) {
        return new AnalysisProperties(URI.create(uri), Duration.ofSeconds(3), Duration.ofSeconds(15),
                rounds, calls, 64000, 16000);
    }
}
