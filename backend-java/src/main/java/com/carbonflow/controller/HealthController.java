package com.carbonflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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

    @GetMapping({"/api/health/readiness", "/api/v1/health/readiness"})
    public ResponseEntity<Map<String, Object>> readinessCheck() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("database", "UP");
        response.put("timestamp", Instant.now().toString());
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            response.put("status", "DOWN");
            response.put("database", "DOWN");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
}
