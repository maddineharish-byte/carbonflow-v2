package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.CalculationRequest;
import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.EmissionFactor;
import com.carbonflow.repository.DataStore;
import com.carbonflow.service.GhgCalculationEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class CalculationController {

    private final DataStore dataStore;
    private final GhgCalculationEngine calculationEngine;

    public CalculationController(DataStore dataStore, GhgCalculationEngine calculationEngine) {
        this.dataStore = dataStore;
        this.calculationEngine = calculationEngine;
    }

    @GetMapping("/factors")
    @PreAuthorize("hasAuthority('PERMISSION_emission_factors.read')")
    public ResponseEntity<ApiResponse<List<EmissionFactor>>> getEmissionFactors() {
        return ResponseEntity.ok(ApiResponse.ok(List.copyOf(dataStore.emissionFactors.values())));
    }

    @PostMapping("/calculations/run")
    @PreAuthorize("hasAuthority('PERMISSION_calculations.create')")
    public ResponseEntity<ApiResponse<Calculation>> runCalculation(@Valid @RequestBody CalculationRequest req) {
        TenantContext ctx = TenantContext.get();

        ActivityData act = dataStore.activityData.get(req.getActivityDataId());
        if (act == null || !act.getOrganizationId().equals(ctx.getOrganizationId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("NOT_FOUND", "Activity data record not found for tenant."));
        }

        EmissionFactor factor = dataStore.emissionFactors.get(req.getEmissionFactorId());
        if (factor == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("NOT_FOUND", "Emission factor not found."));
        }

        Calculation calc = calculationEngine.executeCalculation(act, factor, ctx.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(calc, "GHG calculation executed with deterministic SHA-256 seal."));
    }
}
