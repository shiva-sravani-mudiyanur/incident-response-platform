package com.umass.diagnostics.exception;
/** Carries only a stable code and a safe, locally authored message. */
public class DiagnosticException extends RuntimeException {
    private final String code;
    public DiagnosticException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }
    public String code() { return code; }
}
