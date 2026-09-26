package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.Facility;
import com.carbonflow.repository.DataStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

    private final DataStore dataStore;

    public FacilityController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.read')")
    public ResponseEntity<ApiResponse<List<Facility>>> getFacilities() {
        TenantContext ctx = TenantContext.get();
        List<Facility> list = dataStore.facilities.values().stream()
                .filter(f -> f.getOrganizationId().equals(ctx.getOrganizationId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.create')")
    public ResponseEntity<ApiResponse<Facility>> createFacility(@RequestBody Facility req) {
        TenantContext ctx = TenantContext.get();
        req.setId("fac-" + UUID.randomUUID().toString().substring(0, 8));
        req.setOrganizationId(ctx.getOrganizationId());
        req.setCreatedAt(Instant.now());
        dataStore.facilities.put(req.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(req, "Facility registered successfully."));
    }
}
