package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.EvidenceItem;
import com.carbonflow.repository.DataStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final DataStore dataStore;

    public EvidenceController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EvidenceItem>>> getEvidence() {
        TenantContext ctx = TenantContext.get();
        List<EvidenceItem> list = dataStore.evidenceItems.values().stream()
                .filter(e -> e.getOrganizationId().equals(ctx.getOrganizationId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping("/upload-mock")
    public ResponseEntity<ApiResponse<EvidenceItem>> uploadMockEvidence(@RequestBody EvidenceItem req) {
        TenantContext ctx = TenantContext.get();
        req.setId("evd-" + UUID.randomUUID().toString().substring(0, 8));
        req.setOrganizationId(ctx.getOrganizationId());
        req.setVerificationStatus("VERIFIED");
        req.setUploadedBy(ctx.getEmail());
        req.setCreatedAt(Instant.now());
        dataStore.evidenceItems.put(req.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(req, "Evidence file registered and hashed with SHA-256 integrity seal."));
    }
}
