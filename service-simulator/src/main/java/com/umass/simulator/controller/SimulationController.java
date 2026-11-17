package com.umass.simulator.controller;

import com.umass.simulator.dto.SimulationResponse;
import com.umass.simulator.model.Scenario;
import com.umass.simulator.service.SimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/simulate")
public class SimulationController {
    private final SimulationService service;

    public SimulationController(SimulationService service) {
        this.service = service;
    }

    @PostMapping("/normal")
    public SimulationResponse normal() {
        return service.simulate(Scenario.NORMAL);
    }

    @PostMapping("/database-failure")
    public SimulationResponse databaseFailure() {
        return service.simulate(Scenario.DATABASE_FAILURE);
    }

    @PostMapping("/payment-failure")
    public SimulationResponse paymentFailure() {
        return service.simulate(Scenario.PAYMENT_FAILURE);
    }

    @PostMapping("/cascade-failure")
    public SimulationResponse cascadeFailure() {
        return service.simulate(Scenario.CASCADE_FAILURE);
    }
}
