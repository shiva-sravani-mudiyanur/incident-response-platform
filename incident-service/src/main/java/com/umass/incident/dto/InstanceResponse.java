package com.umass.incident.dto;

import java.time.Instant;

public record InstanceResponse(String serviceName, String hostname, Instant timestamp) {
}
