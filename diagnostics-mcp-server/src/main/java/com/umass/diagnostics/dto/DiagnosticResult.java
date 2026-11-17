package com.umass.diagnostics.dto;

public record DiagnosticResult<T>(boolean success, T data, DiagnosticError error) {
    public static <T> DiagnosticResult<T> ok(T data) {
        return new DiagnosticResult<>(true, data, null);
    }
    public static <T> DiagnosticResult<T> failure(String code, String message) {
        return new DiagnosticResult<>(false, null, new DiagnosticError(code, message));
    }
}
