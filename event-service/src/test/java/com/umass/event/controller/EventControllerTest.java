package com.umass.event.controller;

import com.umass.event.dto.ServiceEvent;
import com.umass.event.exception.GlobalExceptionHandler;
import com.umass.event.exception.EventPublishException;
import com.umass.event.model.EventSeverity;
import com.umass.event.model.EventType;
import com.umass.event.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {
    @Mock
    private EventService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EventController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void validEventReturns202AndServiceReceivesValidObject() throws Exception {
        UUID acceptedId = UUID.randomUUID();
        when(service.accept(any(ServiceEvent.class))).thenReturn(event(acceptedId));

        mvc.perform(post("/api/events").contentType(APPLICATION_JSON).content("""
                {"serviceName":"payment-service","eventType":"DATABASE_ERROR","severity":"ERROR",
                 "message":"Database connection failed","timestamp":"2026-11-15T12:00:00Z",
                 "traceId":"trace-123","metadata":{"database":"payments"}}
                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(acceptedId.toString()))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(service).accept(event(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingServiceNameReturns400(String serviceName) throws Exception {
        var payload = payload();
        payload.put("serviceName", serviceName);

        mvc.perform(post("/api/events").contentType(APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.serviceName").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"severity", "serviceName", "eventType", "message", "timestamp"})
    void missingRequiredFieldReturns400(String field) throws Exception {
        var payload = payload();
        payload.remove(field);

        mvc.perform(post("/api/events").contentType(APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void invalidEventPayloadReturns400(String body) throws Exception {
        mvc.perform(post("/api/events").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serviceName", "message", "traceId"})
    void embeddedNulReturns400BeforePublication(String field) throws Exception {
        org.mockito.Mockito.lenient().when(service.accept(any(ServiceEvent.class))).thenReturn(event(UUID.randomUUID()));

        mvc.perform(post("/api/events").contentType(APPLICATION_JSON)
                        .content(invalidValue(field, "before\0after")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());
        verifyNoInteractions(service);
    }

    private static Stream<String> invalidPayloads() {
        var nullMetadataValue = new HashMap<String, String>();
        nullMetadataValue.put("key", null);
        return Stream.of("", "{}", "null", "{", "[]",
                invalidValue("eventId", "not-a-uuid"),
                invalidValue("eventType", "INVALID"),
                invalidValue("severity", "INVALID"),
                invalidValue("timestamp", "not-an-instant"),
                invalidValue("message", " "),
                invalidValue("metadata", Map.of("key", Map.of("nested", "object"))),
                invalidValue("metadata", Map.of("", "value")),
                invalidValue("metadata", nullMetadataValue));
    }

    @ParameterizedTest
    @MethodSource("oversizedFields")
    void oversizedFieldsReturn400(String field, Object value) throws Exception {
        mvc.perform(post("/api/events").contentType(APPLICATION_JSON).content(invalidValue(field, value)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> oversizedFields() {
        var metadata = new HashMap<String, String>();
        for (int i = 0; i < 51; i++) {
            metadata.put("key" + i, "value");
        }
        return Stream.of(Arguments.of("serviceName", "s".repeat(101)),
                Arguments.of("message", "m".repeat(4001)),
                Arguments.of("traceId", "t".repeat(129)),
                Arguments.of("metadata", metadata),
                Arguments.of("metadata", Map.of("k".repeat(101), "value")),
                Arguments.of("metadata", Map.of("key", "v".repeat(1001))));
    }

    private static String invalidValue(String field, Object value) {
        var payload = payload();
        payload.put(field, value);
        return JsonMapper.builder().build().writeValueAsString(payload);
    }

    private static Map<String, Object> payload() {
        var payload = new HashMap<String, Object>();
        payload.put("serviceName", "payment-service");
        payload.put("eventType", "DATABASE_ERROR");
        payload.put("severity", "ERROR");
        payload.put("message", "Database connection failed");
        payload.put("timestamp", "2026-11-15T12:00:00Z");
        return payload;
    }

    private ServiceEvent event(UUID id) {
        return new ServiceEvent(id, "payment-service", EventType.DATABASE_ERROR,
                EventSeverity.ERROR, "Database connection failed", Instant.parse("2026-11-15T12:00:00Z"),
                "trace-123", Map.of("database", "payments"));
    }

    @Test
    void publicationFailureReturns503RatherThanFalseAcceptance() throws Exception {
        when(service.accept(any())).thenThrow(new EventPublishException(new IllegalStateException("Unavailable")));

        mvc.perform(post("/api/events").contentType(APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(payload())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
