package com.umass.incident.controller;

import com.umass.incident.service.InstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InstanceControllerTest {
    @Test
    void returnsReadOnlyInstanceJsonWithoutCache() throws Exception {
        var now = Instant.parse("2026-11-18T12:00:00Z");
        var service = new InstanceService(Clock.fixed(now, ZoneOffset.UTC), "incident-service", "incident-service-pod-a");
        var mvc = MockMvcBuilders.standaloneSetup(new InstanceController(service)).build();
        mvc.perform(get("/api/instance")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.serviceName").value("incident-service"))
                .andExpect(jsonPath("$.hostname").value("incident-service-pod-a"))
                .andExpect(jsonPath("$.timestamp").value(now.toString()));
    }

    @Test
    void doesNotExposeAWriteOperation() throws Exception {
        var service = new InstanceService(Clock.systemUTC(), "incident-service", "incident-service-pod-a");
        var mvc = MockMvcBuilders.standaloneSetup(new InstanceController(service)).build();
        mvc.perform(post("/api/instance")).andExpect(status().isMethodNotAllowed());
    }
}
