package com.umass.simulator.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SimulationFailedException.class)
    public ProblemDetail handleSimulationFailure(SimulationFailedException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Simulation stopped before all events could be confirmed by event-service.");
        detail.setProperty("scenario", exception.getScenario());
        detail.setProperty("traceId", exception.getTraceId());
        detail.setProperty("acceptedEventCount", exception.getAcceptedEventIds().size());
        detail.setProperty("acceptedEventIds", exception.getAcceptedEventIds());
        log.warn("Simulation stopped scenario={} traceId={} acceptedEventCount={}",
                exception.getScenario(), exception.getTraceId(), exception.getAcceptedEventIds().size());
        return detail;
    }
}
