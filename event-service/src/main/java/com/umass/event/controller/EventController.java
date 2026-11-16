package com.umass.event.controller;

import com.umass.event.dto.EventAcceptedResponse;
import com.umass.event.dto.ServiceEvent;
import com.umass.event.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EventAcceptedResponse> accept(@Valid @RequestBody ServiceEvent event) {
        ServiceEvent accepted = service.accept(event);
        return ResponseEntity.accepted().body(new EventAcceptedResponse(accepted.eventId()));
    }
}
