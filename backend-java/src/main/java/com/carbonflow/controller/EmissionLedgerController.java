package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.repository.DataStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emissions")
public class EmissionLedgerController {

    private final DataStore dataStore;

    public EmissionLedgerController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_reports.read')")
    public ResponseEntity<ApiResponse<List<EmissionRecord>>> getEmissionRecords() {
        TenantContext ctx = TenantContext.get();
        List<EmissionRecord> list = dataStore.emissionRecords.values().stream()
                .filter(e -> e.getOrganizationId().equals(ctx.getOrganizationId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }
}
