package com.umass.incident.exception;

import com.umass.incident.controller.ServiceController;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ServiceController.class)
public class ServiceOperationsExceptionHandler {
    @ExceptionHandler(ServiceNotFoundException.class)
    public ProblemDetail notFound(ServiceNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidServiceQueryException.class)
    public ProblemDetail invalidQuery(InvalidServiceQueryException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ProblemDetail unavailable(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Operational data is temporarily unavailable. Try again later.");
    }
}
