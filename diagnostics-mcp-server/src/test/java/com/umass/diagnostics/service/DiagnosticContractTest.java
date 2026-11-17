package com.umass.diagnostics.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticContractTest {
    @Test
    void resultAndErrorHaveStableRecordContracts() throws Exception {
        Class<?> result = assertDoesNotThrow(() -> Class.forName("com.umass.diagnostics.dto.DiagnosticResult"),
                "DiagnosticResult must exist before diagnostics can return structured outcomes");
        assertTrue(result.isRecord());
        assertEquals("success,data,error", String.join(",", Arrays.stream(result.getRecordComponents()).map(c -> c.getName()).toList()));
        Class<?> error = Class.forName("com.umass.diagnostics.dto.DiagnosticError");
        assertTrue(error.isRecord());
        assertEquals("code,message", String.join(",", Arrays.stream(error.getRecordComponents()).map(c -> c.getName()).toList()));
    }
}
