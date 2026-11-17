package com.umass.incident.controller;

import com.umass.incident.dto.ServiceHealthResponse;
import com.umass.incident.exception.GlobalExceptionHandler;
import com.umass.incident.exception.ServiceOperationsExceptionHandler;
import com.umass.incident.model.HealthStatus;
import com.umass.incident.service.ServiceOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@ExtendWith(MockitoExtension.class)
class ServiceControllerTest {
    @Mock private ServiceOperationsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ServiceController(service))
                .setControllerAdvice(new ServiceOperationsExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void listReturnsHealthDtos() throws Exception {
        when(service.services()).thenReturn(List.of(new ServiceHealthResponse("orders", HealthStatus.HEALTHY, 0, 0, null)));
        mvc.perform(get("/api/services")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceName").value("orders"))
                .andExpect(jsonPath("$[0].status").value("HEALTHY"));
    }

    @Test
    void redisFailureIsUnavailableNotHealthyAndDoesNotExposeConnectionDetails() throws Exception {
        when(service.health("orders")).thenThrow(new DataAccessResourceFailureException("private-connection-details"));
        mvc.perform(get("/api/services/orders/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(not(containsString("private-connection-details"))));
    }

    @Test
    void databaseTransactionFailureIsSanitizedAndUnavailable() throws Exception {
        when(service.health("orders")).thenThrow(new CannotCreateTransactionException("private-connection-details"));
        mvc.perform(get("/api/services/orders/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(not(containsString("private-connection-details"))));
    }
}
