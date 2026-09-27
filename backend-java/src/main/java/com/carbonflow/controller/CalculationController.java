package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.AccountingResponses;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.BatchCalculationRequest;
import com.carbonflow.dto.CalculationRequest;
import com.carbonflow.model.Calculation;
import com.carbonflow.service.CalculationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calculation module (Phase 6): {@code POST /calculations/run},
 * {@code POST /calculations/batch-run} (Node production contract) and the
 * greenfield {@code GET /calculations/:id} audit snapshot (API.md §2.5).
 *
 * <p>{@code GET /factors} from the prototype era is retired — factor reads
 * live behind {@code GET /reference/emission-factors} like the reference
 * backend (ADR-017).
 *
 * <p>Bodies are {@code required = false} so a missing payload reaches the
 * service's contract validation ({@code activityDataId is required.}) exactly
 * like {@code req.body || {}} in Node.
 */
@RestController
@RequestMapping("/api/v1")
public class CalculationController {

    private final CalculationService calculations;

    public CalculationController(CalculationService calculations) {
        this.calculations = calculations;
    }

    @PostMapping("/calculations/run")
    @PreAuthorize("hasAuthority('PERMISSION_calculations.create')")
    public ResponseEntity<ApiResponse<AccountingResponses.CalculationRunResult>> run(
            @RequestBody(required = false) CalculationRequest request) {
        AccountingResponses.CalculationRunResult result =
                calculations.run(org(), userId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result, "Calculation executed deterministically."));
    }

    @PostMapping("/calculations/batch-run")
    @PreAuthorize("hasAuthority('PERMISSION_calculations.create')")
    public ResponseEntity<ApiResponse<AccountingResponses.BatchCalculationResult>> batchRun(
            @RequestBody(required = false) BatchCalculationRequest request) {
        AccountingResponses.BatchCalculationResult result =
                calculations.batchRun(org(), userId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result,
                "Batch calculation completed for " + result.processed + " items."));
    }

    @GetMapping("/calculations/{calculationId}")
    @PreAuthorize("hasAuthority('PERMISSION_calculations.read')")
    public ResponseEntity<ApiResponse<Calculation>> get(@PathVariable String calculationId) {
        return ResponseEntity.ok(ApiResponse.ok(calculations.getCalculation(org(), calculationId)));
    }

    private static String org() {
        return TenantContext.get().getOrganizationId();
    }

    private static String userId() {
        return TenantContext.get().getUserId();
    }
}
