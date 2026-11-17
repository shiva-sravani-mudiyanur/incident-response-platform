package com.umass.analysis.exception;

import org.springframework.http.HttpStatus;

public class AnalysisException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AnalysisException(HttpStatus status, String code, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
