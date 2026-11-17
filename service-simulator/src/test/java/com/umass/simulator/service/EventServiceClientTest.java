package com.umass.simulator.service;

import com.umass.simulator.dto.ServiceEvent;
import com.umass.simulator.model.EventSeverity;
import com.umass.simulator.model.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventServiceClientTest {
    private MockRestServiceServer server;
    private EventServiceClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://event-service.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EventServiceClient(builder.build());
    }

    @Test
    void postsServiceEventJsonToEventServiceAndRequiresAcknowledgement() {
        ServiceEvent event = event();
        server.expect(requestTo("http://event-service.test/api/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.eventId").value(event.eventId().toString()))
                .andExpect(jsonPath("$.serviceName").value("order-service"))
                .andExpect(jsonPath("$.severity").value("ERROR"))
                .andExpect(jsonPath("$.eventType").value("DATABASE_ERROR"))
                .andExpect(jsonPath("$.traceId").value(event.traceId()))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"eventId\":\"" + event.eventId() + "\"}"));

        client.publish(event);

        server.verify();
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 503})
    void unexpectedStatusIsNotTreatedAsAcceptance(int status) {
        ServiceEvent event = event();
        server.expect(requestTo("http://event-service.test/api/events"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"eventId\":\"" + event.eventId() + "\"}"));

        assertThatThrownBy(() -> client.publish(event)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"eventId\":\"11111111-1111-4111-8111-111111111111\"}"})
    void missingOrMismatchedAcknowledgementIdIsRejected(String body) {
        server.expect(requestTo("http://event-service.test/api/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(body));

        assertThatThrownBy(() -> client.publish(event())).isInstanceOf(RestClientException.class);
    }

    @Test
    void emptyAcknowledgementBodyIsRejected() {
        server.expect(requestTo("http://event-service.test/api/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> client.publish(event())).isInstanceOf(RestClientException.class);
    }

    private ServiceEvent event() {
        return new ServiceEvent(UUID.randomUUID(), "order-service", EventType.DATABASE_ERROR, EventSeverity.ERROR,
                "Database timeout", Instant.parse("2026-11-16T12:00:00Z"), UUID.randomUUID().toString());
    }
}
