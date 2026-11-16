package com.umass.event.exception;

public class EventPublishException extends RuntimeException {
    public EventPublishException(Throwable cause) {
        super("Event publication could not be confirmed", cause);
    }
}
