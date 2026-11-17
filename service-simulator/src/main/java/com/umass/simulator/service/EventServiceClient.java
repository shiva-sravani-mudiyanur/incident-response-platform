package com.umass.simulator.service;

import com.umass.simulator.dto.EventAcceptedResponse;
import com.umass.simulator.dto.ServiceEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class EventServiceClient {
    private final RestClient client;

    public EventServiceClient(RestClient client) {
        this.client = client;
    }

    public void publish(ServiceEvent event) {
        var response = client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON)
                .body(event).retrieve().toEntity(EventAcceptedResponse.class);
        if (response.getStatusCode() != HttpStatus.ACCEPTED || response.getBody() == null
                || !event.eventId().equals(response.getBody().eventId())) {
            throw new RestClientException("event-service did not acknowledge the submitted eventId with HTTP 202");
        }
    }
}
