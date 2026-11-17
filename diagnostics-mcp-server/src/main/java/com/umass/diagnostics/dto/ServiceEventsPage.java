package com.umass.diagnostics.dto;
import java.util.List;
public record ServiceEventsPage(String serviceName, List<ServiceEvent> events, Integer page, Integer size,
                                Long totalElements, Integer totalPages) { }
