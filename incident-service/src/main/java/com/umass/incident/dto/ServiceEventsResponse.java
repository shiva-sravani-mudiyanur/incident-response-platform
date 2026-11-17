package com.umass.incident.dto;

import java.util.List;

public record ServiceEventsResponse(String serviceName, List<ServiceEventResponse> events, int page, int size,
                                    long totalElements, int totalPages) {
    public ServiceEventsResponse {
        events = List.copyOf(events);
    }
}
