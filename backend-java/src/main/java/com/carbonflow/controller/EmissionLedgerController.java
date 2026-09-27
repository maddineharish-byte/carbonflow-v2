package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.AccountingResponses.EmissionLedgerResult;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.service.EmissionLedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emission ledger (Phase 6): {@code GET /emissions} — active emission
 * records with the Scope 1 / Scope 2 dual-reporting breakdown
 * ({@code records} + {@code summary}, Node production contract).
 */
@RestController
@RequestMapping("/api/v1/emissions")
public class EmissionLedgerController {

    private final EmissionLedgerService ledger;

    public EmissionLedgerController(EmissionLedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_reports.read')")
    public ResponseEntity<ApiResponse<EmissionLedgerResult>> list(
            @RequestParam(name = "periodId", required = false) String periodId) {
        return ResponseEntity.ok(ApiResponse.ok(ledger.list(org(), periodId)));
    }

    private static String org() {
        return TenantContext.get().getOrganizationId();
    }
}
