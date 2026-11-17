package com.umass.incident.exception;

public class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException(String serviceName) {
        super("Service not found: " + serviceName);
    }
}
