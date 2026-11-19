package com.umass.incident.service;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.exception.InvalidServiceEventException;
import com.umass.incident.observability.EventTraceScope;
import com.umass.incident.observability.IncidentMetrics;
import com.umass.incident.repository.IncidentEventRepository;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
public class EventProcessingService {
    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);
    private final IncidentEventRepository repository;
    private final Validator validator;
    private final Clock clock;
    private final CorrelationService correlation;
    private final IncidentMetrics metrics;

    public EventProcessingService(IncidentEventRepository repository, Validator validator, Clock clock,
                                  CorrelationService correlation, IncidentMetrics metrics) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
        this.correlation = correlation;
        this.metrics = metrics;
    }

    @Transactional
    public void process(ServiceEvent event) {
        var attempt = metrics.begin();
        try (var trace = new EventTraceScope(event == null ? null : event.traceId())) {
            try {
                if (event == null || !validator.validate(event).isEmpty()) {
                    throw new InvalidServiceEventException("Consumed event failed validation");
                }
                // Atomically claim the eventId in the same transaction as incident creation.
                // A check followed by save would race with another delivery of the same ID.
                int inserted = repository.insertIfAbsent(event.eventId(), event.serviceName(), event.eventType().name(),
                        event.severity().name(), event.message(), event.timestamp().truncatedTo(ChronoUnit.MICROS),
                        event.traceId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
                if (inserted == 0) {
                    log.atInfo().addKeyValue("eventId", event.eventId()).log("Duplicate event ignored");
                    attempt.duplicate();
                    return;
                }
                correlation.correlate(event);
                attempt.success();
                log.atInfo().addKeyValue("eventId", event.eventId()).addKeyValue("eventType", event.eventType())
                        .addKeyValue("severity", event.severity()).log("Event processing prepared for commit");
            } catch (RuntimeException | Error failure) {
                attempt.error();
                log.atWarn().addKeyValue("eventId", event == null ? null : event.eventId()).log("Event processing failed");
                throw failure;
            }
        }
    }
}
