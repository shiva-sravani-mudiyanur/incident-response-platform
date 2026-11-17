package com.umass.simulator.dto;

import com.umass.simulator.model.Scenario;
import java.util.List;
import java.util.UUID;

public record SimulationResponse(Scenario scenario, String traceId, int eventCount, List<UUID> eventIds) {
    public SimulationResponse {
        eventIds = List.copyOf(eventIds);
    }
}
