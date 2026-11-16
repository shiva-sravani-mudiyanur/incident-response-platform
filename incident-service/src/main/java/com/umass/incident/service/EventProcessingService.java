package com.umass.incident.service;

import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.exception.InvalidServiceEventException;
import com.umass.incident.model.IncidentEvent;
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

    public EventProcessingService(IncidentEventRepository repository, Validator validator, Clock clock,
                                  CorrelationService correlation) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
        this.correlation = correlation;
    }

    @Transactional
    public void process(ServiceEvent event) {
        if (event == null || !validator.validate(event).isEmpty()) {
            throw new InvalidServiceEventException("Consumed event failed validation");
        }
        // Kafka redelivery must not count an already committed event a second time.
        if (repository.existsById(event.eventId())) return;
        String logServiceName = event.serviceName().replace('\r', '_').replace('\n', '_');
        log.info("Persisting event eventId={} serviceName={} eventType={} severity={}",
                event.eventId(), logServiceName, event.eventType(), event.severity());
        repository.save(new IncidentEvent(event.eventId(), event.serviceName(), event.eventType(),
                event.severity(), event.message(), event.timestamp().truncatedTo(ChronoUnit.MICROS),
                event.traceId(), clock.instant().truncatedTo(ChronoUnit.MICROS)));
        correlation.correlate(event);
    }
}
