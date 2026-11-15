package com.umass.incident.controller;

import com.umass.incident.dto.CreateIncidentRequest;
import com.umass.incident.dto.IncidentResponse;
import com.umass.incident.exception.IncidentNotFoundException;
import com.umass.incident.exception.GlobalExceptionHandler;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IncidentControllerTest {
    private MockMvc mvc;
    @Mock
    private IncidentService service;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new IncidentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private IncidentResponse response(UUID id) {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        return new IncidentResponse(id, "Payment failures", IncidentStatus.OPEN,
                IncidentSeverity.HIGH, "payment-service", now, now, 5);
    }

    @Test
    void successfulCreation() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.createIncident(any(CreateIncidentRequest.class))).thenReturn(response(id));

        mvc.perform(post("/api/incidents").contentType(APPLICATION_JSON).content("""
                {"title":"Payment failures","severity":"HIGH","rootService":"payment-service","errorCount":5}
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/incidents/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-15T12:00:00Z"));
    }

    @Test
    void successfulRetrieval() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getIncident(id)).thenReturn(response(id));

        mvc.perform(get("/api/incidents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void listIncidents() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getIncidents(null)).thenReturn(List.of(response(id)));

        mvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void filterByStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getIncidents(IncidentStatus.OPEN)).thenReturn(List.of(response(id)));

        mvc.perform(get("/api/incidents").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void missingIncidentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getIncident(id)).thenThrow(new IncidentNotFoundException(id));

        mvc.perform(get("/api/incidents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Incident not found: " + id));
    }

    @Test
    void invalidRequestReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/incidents").contentType(APPLICATION_JSON).content("""
                {"title":" ","severity":null,"rootService":"","errorCount":-1}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.severity").exists())
                .andExpect(jsonPath("$.errors.rootService").exists())
                .andExpect(jsonPath("$.errors.errorCount").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"title\":\"Failure\",\"severity\":\"URGENT\",\"rootService\":\"payment\",\"errorCount\":1}"})
    void malformedOrMissingFieldsReturn400(String body) throws Exception {
        mvc.perform(post("/api/incidents").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void invalidStatusFilterReturns400() throws Exception {
        mvc.perform(get("/api/incidents").param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'status'."));
        verifyNoInteractions(service);
    }

    @Test
    void malformedIdReturns400() throws Exception {
        mvc.perform(get("/api/incidents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'id'."));
        verifyNoInteractions(service);
    }

    @Test
    void updateStatus() throws Exception {
        UUID id = UUID.randomUUID();
        var original = response(id);
        var updated = new IncidentResponse(id, original.title(), IncidentStatus.RESOLVED,
                original.severity(), original.rootService(), original.createdAt(),
                original.updatedAt().plusSeconds(1), original.errorCount());
        when(service.updateStatus(id, IncidentStatus.RESOLVED)).thenReturn(updated);

        mvc.perform(patch("/api/incidents/{id}/status", id).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"INVALID\"}"})
    void invalidStatusUpdateReturns400(String body) throws Exception {
        mvc.perform(patch("/api/incidents/{id}/status", UUID.randomUUID())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void updateMissingIncidentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.updateStatus(id, IncidentStatus.RESOLVED)).thenThrow(new IncidentNotFoundException(id));

        mvc.perform(patch("/api/incidents/{id}/status", id).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound());
    }
}
