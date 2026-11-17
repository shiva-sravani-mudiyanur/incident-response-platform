package com.umass.simulator.service;

import com.umass.simulator.config.SimulatorProperties;
import com.umass.simulator.dto.ServiceEvent;
import com.umass.simulator.dto.SimulationResponse;
import com.umass.simulator.exception.SimulationFailedException;
import com.umass.simulator.model.EventSeverity;
import com.umass.simulator.model.EventType;
import com.umass.simulator.model.Scenario;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SimulationService {
    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
    private final EventServiceClient client;
    private final Clock clock;
    private final SimulatorProperties properties;
    private final SimulationDelay delay;

    public SimulationService(EventServiceClient client, Clock clock, SimulatorProperties properties, SimulationDelay delay) {
        this.client = client;
        this.clock = clock;
        this.properties = properties;
        this.delay = delay;
    }

    public SimulationResponse simulate(Scenario scenario) {
        String traceId = UUID.randomUUID().toString();
        var accepted = new ArrayList<UUID>();
        try {
            switch (scenario) {
                case NORMAL -> {
                    for (String name : List.of("order-service", "payment-service", "inventory-service")) {
                        publishBatch(name, EventType.SERVICE_STARTED, EventSeverity.INFO,
                                name + " started successfully and is ready to accept requests", 1, traceId, accepted);
                    }
                }
                case DATABASE_FAILURE -> publishBatch("order-service", EventType.DATABASE_ERROR, EventSeverity.ERROR,
                        "Order database query timed out after 2000 ms while loading order state",
                        properties.failureEventCount(), traceId, accepted);
                case PAYMENT_FAILURE -> publishBatch("payment-service", EventType.TIMEOUT, EventSeverity.ERROR,
                        "Payment authorization timed out after 3000 ms waiting for the payment gateway",
                        properties.failureEventCount(), traceId, accepted);
                case CASCADE_FAILURE -> {
                    publishBatch("order-service", EventType.DATABASE_ERROR, EventSeverity.CRITICAL,
                            "Order database query timed out; order state is unavailable",
                            properties.failureEventCount(), traceId, accepted);
                    delay.pause(properties.cascadeDelay());
                    publishBatch("payment-service", EventType.DEPENDENCY_ERROR, EventSeverity.ERROR,
                            "Payment authorization failed because order-service could not confirm order state",
                            properties.failureEventCount(), traceId, accepted);
                    delay.pause(properties.cascadeDelay());
                    publishBatch("inventory-service", EventType.DEPENDENCY_ERROR, EventSeverity.ERROR,
                            "Inventory reservation failed because payment-service authorization was unavailable",
                            properties.failureEventCount(), traceId, accepted);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SimulationFailedException(scenario, traceId, accepted, exception);
        } catch (RestClientException exception) {
            throw new SimulationFailedException(scenario, traceId, accepted, exception);
        }
        log.info("Submitted simulation scenario={} traceId={} eventCount={}", scenario, traceId, accepted.size());
        return new SimulationResponse(scenario, traceId, accepted.size(), accepted);
    }

    private void publishBatch(String serviceName, EventType type, EventSeverity severity, String message,
                              int count, String traceId, List<UUID> accepted) {
        for (int i = 0; i < count; i++) {
            var event = new ServiceEvent(UUID.randomUUID(), serviceName, type, severity,
                    message + " (event " + (i + 1) + "/" + count + ")", clock.instant(), traceId);
            client.publish(event);
            accepted.add(event.eventId());
        }
    }
}
