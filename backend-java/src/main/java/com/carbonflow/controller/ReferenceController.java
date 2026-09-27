package com.carbonflow.controller;

import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.CalculationMethodology;
import com.carbonflow.model.EmissionFactor;
import com.carbonflow.model.GwpSet;
import com.carbonflow.service.ReferenceDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reference data (Phase 6): {@code GET /reference/gwp-sets} and
 * {@code GET /reference/emission-factors} — the Node production contract
 * (gwp-sets requires only authentication; emission-factors require
 * {@code emission_factors.read} — the reference backend's exact gates), plus
 * the greenfield {@code GET /reference/methodologies} lookup (ADR-017:
 * reference-only table, no methodology field on calculations).
 */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    private final ReferenceDataService referenceData;

    public ReferenceController(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    /** Node gates this route behind authentication only — no permission. */
    @GetMapping("/gwp-sets")
    public ResponseEntity<ApiResponse<List<GwpSet>>> gwpSets() {
        return ResponseEntity.ok(ApiResponse.ok(referenceData.gwpSets()));
    }

    @GetMapping("/emission-factors")
    @PreAuthorize("hasAuthority('PERMISSION_emission_factors.read')")
    public ResponseEntity<ApiResponse<List<EmissionFactor>>> emissionFactors() {
        return ResponseEntity.ok(ApiResponse.ok(referenceData.emissionFactors()));
    }

    @GetMapping("/methodologies")
    @PreAuthorize("hasAuthority('PERMISSION_emission_factors.read')")
    public ResponseEntity<ApiResponse<List<CalculationMethodology>>> methodologies() {
        return ResponseEntity.ok(ApiResponse.ok(referenceData.methodologies()));
    }
}
