package com.umass.incident.controller;

import com.umass.incident.dto.ServiceHealthResponse;
import com.umass.incident.dto.ServiceEventsResponse;
import com.umass.incident.service.ServiceOperationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/services")
public class ServiceController {
    private final ServiceOperationsService service;

    public ServiceController(ServiceOperationsService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServiceHealthResponse> services() {
        return service.services();
    }

    @GetMapping("/{serviceName}/health")
    public ServiceHealthResponse health(@PathVariable String serviceName) {
        return service.health(serviceName);
    }

    @GetMapping("/{serviceName}/events")
    public ServiceEventsResponse events(@PathVariable String serviceName,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.events(serviceName, page, size);
    }
}
