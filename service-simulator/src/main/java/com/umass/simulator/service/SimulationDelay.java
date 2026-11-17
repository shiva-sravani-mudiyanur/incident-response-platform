package com.umass.simulator.service;

import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class SimulationDelay {
    public void pause(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
