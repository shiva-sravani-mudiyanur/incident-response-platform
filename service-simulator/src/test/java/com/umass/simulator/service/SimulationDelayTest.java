package com.umass.simulator.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationDelayTest {
    @Test
    void zeroDelayCompletes() throws Exception {
        new SimulationDelay().pause(Duration.ZERO);
    }

    @Test
    void interruptedWaitIsReportedToCaller() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new SimulationDelay().pause(Duration.ofMillis(1)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
