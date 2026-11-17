package com.umass.incident.exception;

public class InvalidServiceQueryException extends RuntimeException {
    public InvalidServiceQueryException(String message) {
        super(message);
    }
}
