package com.umass.incident.service;

import com.umass.incident.dto.ServiceHealthResponse;
import com.umass.incident.dto.ServiceEventResponse;
import com.umass.incident.dto.ServiceEventsResponse;
import com.umass.incident.exception.InvalidServiceQueryException;
import com.umass.incident.exception.ServiceNotFoundException;
import com.umass.incident.model.IncidentEvent;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentEventRepository;
import com.umass.incident.repository.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ServiceOperationsService {
    private static final List<IncidentStatus> ACTIVE = List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING);
    private final IncidentEventRepository events;
    private final IncidentRepository incidents;
    private final RedisRecentErrors recent;
    private final HealthStatusCalculator calculator;

    public ServiceOperationsService(IncidentEventRepository events, IncidentRepository incidents,
                                    RedisRecentErrors recent, HealthStatusCalculator calculator) {
        this.events = events;
        this.incidents = incidents;
        this.recent = recent;
        this.calculator = calculator;
    }

    public ServiceHealthResponse health(String serviceName) {
        requireKnownService(serviceName);
        return healthOfKnownService(serviceName);
    }

    public List<ServiceHealthResponse> services() {
        return events.findKnownServiceNames().stream().map(this::healthOfKnownService).toList();
    }

    public ServiceEventsResponse events(String serviceName, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidServiceQueryException("page must be nonnegative and size must be between 1 and 100.");
        }
        requireKnownService(serviceName);
        var history = events.findByServiceName(serviceName, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("processedAt"), Sort.Order.asc("eventId"))));
        return new ServiceEventsResponse(serviceName, history.getContent().stream().map(ServiceEventResponse::from).toList(),
                history.getNumber(), history.getSize(), history.getTotalElements(), history.getTotalPages());
    }

    private ServiceHealthResponse healthOfKnownService(String name) {
        var active = incidents.findByRootServiceAndStatusIn(name, ACTIVE);
        boolean severe = active.stream().anyMatch(incident -> incident.getSeverity() == IncidentSeverity.HIGH
                || incident.getSeverity() == IncidentSeverity.CRITICAL);
        long errors = recent.countLastMinute(name);
        var last = events.findFirstByServiceNameOrderByTimestampDescEventIdAsc(name)
                .map(IncidentEvent::getTimestamp).orElse(null);
        return new ServiceHealthResponse(name, calculator.calculate(errors, severe), errors, active.size(), last);
    }

    private void requireKnownService(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new InvalidServiceQueryException("serviceName must contain 1 to 100 characters and not be blank.");
        }
        if (!events.existsByServiceName(name) && !incidents.existsByRootService(name)) {
            throw new ServiceNotFoundException(name);
        }
    }
}
