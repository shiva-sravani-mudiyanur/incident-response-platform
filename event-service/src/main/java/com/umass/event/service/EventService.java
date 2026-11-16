package com.umass.event.service;

import com.umass.event.dto.ServiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    public ServiceEvent accept(ServiceEvent event) {
        UUID eventId = event.eventId() == null ? UUID.randomUUID() : event.eventId();
        Map<String, String> metadata = event.metadata() == null ? Map.of() : Map.copyOf(event.metadata());
        ServiceEvent accepted = new ServiceEvent(eventId, event.serviceName(), event.eventType(),
                event.severity(), event.message(), event.timestamp(), event.traceId(), metadata);
        // Log identifiers, not potentially sensitive message/metadata payloads.
        String logServiceName = accepted.serviceName().replace('\r', '_').replace('\n', '_');
        log.info("Accepted event eventId={} serviceName={} eventType={} severity={} timestamp={}",
                accepted.eventId(), logServiceName, accepted.eventType(), accepted.severity(), accepted.timestamp());
        return accepted;
    }
}
