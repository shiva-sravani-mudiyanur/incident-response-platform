package com.umass.incident.controller;

import com.umass.incident.dto.InstanceResponse;
import com.umass.incident.service.InstanceService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InstanceController {
    private final InstanceService service;

    public InstanceController(InstanceService service) {
        this.service = service;
    }

    @GetMapping("/api/instance")
    public ResponseEntity<InstanceResponse> instance() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.instance());
    }
}
