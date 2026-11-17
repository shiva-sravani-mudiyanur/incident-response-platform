package com.umass.simulator.exception;

import com.umass.simulator.model.Scenario;
import java.util.List;
import java.util.UUID;

public class SimulationFailedException extends RuntimeException {
    private final Scenario scenario;
    private final String traceId;
    private final List<UUID> acceptedEventIds;

    public SimulationFailedException(Scenario scenario, String traceId, List<UUID> acceptedEventIds, Throwable cause) {
        super("Simulation could not submit all events to event-service", cause);
        this.scenario = scenario;
        this.traceId = traceId;
        this.acceptedEventIds = List.copyOf(acceptedEventIds);
    }

    public Scenario getScenario() { return scenario; }
    public String getTraceId() { return traceId; }
    public List<UUID> getAcceptedEventIds() { return acceptedEventIds; }
}
