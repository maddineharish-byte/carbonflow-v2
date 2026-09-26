package com.carbonflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/api/health", "/api/v1/health"})
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "CarbonFlow Java Spring Boot Enterprise GHG Accounting Engine",
                "version", "1.0.0-PRO",
                "runtime", "Java " + System.getProperty("java.version"),
                "timestamp", Instant.now().toString()
        ));
    }
}
