package com.umass.incident.service;

import com.umass.incident.dto.InstanceResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class InstanceService {
    private final Clock clock;
    private final String serviceName;
    private final String hostname;

    public InstanceService(Clock clock, @Value("${spring.application.name}") String serviceName,
                           @Qualifier("instanceHostname") String hostname) {
        this.clock = clock;
        this.serviceName = serviceName;
        this.hostname = hostname;
    }

    public InstanceResponse instance() {
        return new InstanceResponse(serviceName, hostname, clock.instant());
    }
}
