package com.umass.diagnostics.service;

import com.umass.diagnostics.exception.DiagnosticException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IncidentServiceClientTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final String INCIDENT = """
        {"id":"11111111-1111-1111-1111-111111111111","title":"Outage","status":"OPEN","severity":"HIGH",
         "rootService":"orders","createdAt":"2026-11-17T09:00:00Z","updatedAt":"2026-11-17T09:00:00Z","errorCount":2}
        """;
    MockRestServiceServer server;
    IncidentServiceClient client;
    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://incident.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IncidentServiceClient(builder.build());
    }
    @Test void retrievesHealthAndEncodesTheLiteralServiceSegment() {
        String name = "orders ?#%&=";
        server.expect(requestTo("http://incident.test/api/services/orders%20%3F%23%25%26%3D/health"))
                .andRespond(withSuccess("{\"serviceName\":\"" + name + "\",\"status\":\"HEALTHY\",\"errorsLastMinute\":30,\"activeIncidentCount\":0}", MediaType.APPLICATION_JSON));
        assertEquals(30L, client.getServiceHealth(name).errorsLastMinute());
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"serviceName\":\"orders\",\"status\":\"HEALTHY\",\"errorsLastMinute\":-1,\"activeIncidentCount\":0}",
            "{\"serviceName\":\"wrong\",\"status\":\"HEALTHY\",\"errorsLastMinute\":0,\"activeIncidentCount\":0}",
            "{\"serviceName\":\"orders\",\"status\":\"HEALTHY\",\"errorsLastMinute\":0}",
            "{\"serviceName\":\"orders\",\"status\":\"MAGIC\",\"errorsLastMinute\":0,\"activeIncidentCount\":0}"})
    void rejectsMalformedHealth(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class, () -> client.getServiceHealth("orders")).code());
    }
    @Test void retrievesEmptyHistoryWithExplicitPagination() {
        server.expect(requestTo("http://incident.test/api/services/orders/events?page=0&size=2"))
                .andRespond(withSuccess("{\"serviceName\":\"orders\",\"events\":[],\"page\":0,\"size\":2,\"totalElements\":0,\"totalPages\":0}", MediaType.APPLICATION_JSON));
        assertTrue(client.getServiceEvents("orders", 0, 2).events().isEmpty());
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{}",
            "{\"serviceName\":\"orders\",\"events\":[],\"page\":1,\"size\":2,\"totalElements\":0,\"totalPages\":0}",
            "{\"serviceName\":\"wrong\",\"events\":[],\"page\":0,\"size\":2,\"totalElements\":0,\"totalPages\":0}",
            "{\"serviceName\":\"orders\",\"events\":[],\"page\":0,\"size\":2,\"totalElements\":1,\"totalPages\":1}",
            "{\"serviceName\":\"orders\",\"events\":[],\"page\":0,\"size\":2,\"totalElements\":0,\"totalPages\":1}",
            "{\"serviceName\":\"orders\",\"events\":[],\"page\":0,\"totalElements\":0,\"totalPages\":0}"})
    void rejectsInconsistentHistoryPage(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class, () -> client.getServiceEvents("orders",0,2)).code());
    }
    @Test void rejectsIncompleteHistoryEvent() {
        server.expect(anything()).andRespond(withSuccess("{\"serviceName\":\"orders\",\"events\":[{}],\"page\":0,\"size\":2,\"totalElements\":1,\"totalPages\":1}", MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class, () -> client.getServiceEvents("orders",0,2)).code());
    }
    static final String EVENT = """
        {"eventId":"22222222-2222-2222-2222-222222222222","serviceName":"orders","eventType":"TIMEOUT",
         "severity":"ERROR","message":"Timeout","timestamp":"2020-01-01T00:00:00Z","traceId":null,"processedAt":"2026-11-17T09:00:00Z"}
        """;
    @Test void decodesValidHistoryWithoutConfusingSourceAndProcessingTime() {
        server.expect(anything()).andRespond(withSuccess(eventPage(EVENT),MediaType.APPLICATION_JSON));
        var event = client.getServiceEvents("orders",0,2).events().getFirst();
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z"),event.timestamp());
        assertEquals(java.time.Instant.parse("2026-11-17T09:00:00Z"),event.processedAt());
    }
    @ParameterizedTest @ValueSource(strings={"eventId","serviceName","eventType","severity","message","timestamp","processedAt"})
    void rejectsHistoryWithMissingRequiredEventField(String field) {
        String invalid = EVENT.replaceAll("\"" + field + "\"\\s*:\\s*\"[^\"]*\"", "\"" + field + "\":null");
        server.expect(anything()).andRespond(withSuccess(eventPage(invalid),MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE",assertThrows(DiagnosticException.class,()->client.getServiceEvents("orders",0,2)).code());
    }
    static String eventPage(String event) {
        return "{\"serviceName\":\"orders\",\"events\":[" + event + "],\"page\":0,\"size\":2,\"totalElements\":1,\"totalPages\":1}";
    }
    @Test void retrievesAndValidatesIncident() {
        server.expect(requestTo("http://incident.test/api/incidents/" + ID)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(INCIDENT, MediaType.APPLICATION_JSON));
        var result = client.getIncident(ID);
        assertEquals(ID, result.id());
        assertEquals("orders", result.rootService());
        server.verify();
    }
    @ParameterizedTest @ValueSource(ints={404,429,500,503,400,302})
    void httpErrorsUseSafeStructuredCodes(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body("secret upstream URL/password"));
        var error = assertThrows(DiagnosticException.class, () -> client.getIncident(ID));
        assertEquals(status == 404 ? "NOT_FOUND" : status == 429 || status >= 500 ? "DOWNSTREAM_UNAVAILABLE" : "INVALID_DOWNSTREAM_RESPONSE", error.code());
        assertFalse(error.getMessage().contains("secret"));
        assertFalse(error.getMessage().contains("http"));
        server.verify();
    }
    @Test void timeoutWhileReadingTheResponseBodyIsUnavailable() throws Exception {
        var response = org.mockito.Mockito.mock(org.springframework.http.client.ClientHttpResponse.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        org.mockito.Mockito.when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        org.mockito.Mockito.when(response.getHeaders()).thenReturn(headers);
        org.mockito.Mockito.when(response.getBody()).thenThrow(new java.net.SocketTimeoutException("secret upstream address"));
        server.expect(anything()).andRespond(request -> response);
        var error = assertThrows(DiagnosticException.class, () -> client.getIncident(ID));
        assertEquals("DOWNSTREAM_UNAVAILABLE", error.code());
        assertFalse(error.getMessage().contains("secret"));
    }
    @Test void connectionFailureIsUnavailableAndDoesNotLeakDetails() {
        server.expect(anything()).andRespond(withException(new IOException("secret http://internal")));
        var error = assertThrows(DiagnosticException.class, () -> client.getIncident(ID));
        assertEquals("DOWNSTREAM_UNAVAILABLE", error.code());
        assertFalse(error.getMessage().contains("internal"));
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "[]", "null", "{bad", "{\"id\":\"1-1-1-1-1\"}"})
    void malformedOrIncompleteIncidentIsNotSuccessful(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class, () -> client.getIncident(ID)).code());
    }
    @Test void mismatchedIncidentIsRejected() {
        server.expect(anything()).andRespond(withSuccess(INCIDENT.replace(ID, "22222222-2222-2222-2222-222222222222"), MediaType.APPLICATION_JSON));
        assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class, () -> client.getIncident(ID)).code());
    }
}
