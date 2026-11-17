package com.umass.simulator.controller;

import com.umass.simulator.dto.SimulationResponse;
import com.umass.simulator.exception.GlobalExceptionHandler;
import com.umass.simulator.exception.SimulationFailedException;
import com.umass.simulator.model.Scenario;
import com.umass.simulator.service.SimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SimulationControllerTest {
    @Mock private SimulationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SimulationController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"normal,NORMAL,3", "database-failure,DATABASE_FAILURE,5",
            "payment-failure,PAYMENT_FAILURE,5", "cascade-failure,CASCADE_FAILURE,15"})
    void postDelegatesToScenarioAndReturnsSubmissionSummary(String path, Scenario scenario, int count) throws Exception {
        String trace = UUID.randomUUID().toString();
        var ids = IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
        when(service.simulate(scenario)).thenReturn(new SimulationResponse(scenario, trace, count, ids));

        mvc.perform(post("/simulate/" + path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value(scenario.name()))
                .andExpect(jsonPath("$.traceId").value(trace))
                .andExpect(jsonPath("$.eventCount").value(count))
                .andExpect(jsonPath("$.eventIds.length()").value(count));

        verify(service).simulate(scenario);
    }

    @Test
    void downstreamFailureReturns502AndConfirmedPartialProgress() throws Exception {
        String trace = UUID.randomUUID().toString();
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(service.simulate(Scenario.DATABASE_FAILURE)).thenThrow(
                new SimulationFailedException(Scenario.DATABASE_FAILURE, trace, ids, new IllegalStateException("Unavailable")));

        mvc.perform(post("/simulate/database-failure"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.scenario").value("DATABASE_FAILURE"))
                .andExpect(jsonPath("$.traceId").value(trace))
                .andExpect(jsonPath("$.acceptedEventCount").value(2))
                .andExpect(jsonPath("$.acceptedEventIds[0]").value(ids.getFirst().toString()));
    }

    @Test
    void getDoesNotTriggerASimulation() throws Exception {
        mvc.perform(get("/simulate/normal")).andExpect(status().isMethodNotAllowed());
    }
}
