package com.carbonflow.service;

import com.carbonflow.dto.CarbonTargetDto;
import com.carbonflow.dto.ReportingRequests.CarbonTargetRequest;
import com.carbonflow.model.CarbonTarget;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.repository.CarbonTargetRepository;
import com.carbonflow.repository.EmissionRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Carbon targets (API.md §2.8; Node parity plus documented deviations).
 *
 * <p>Create is Node-parity ({@code status = ON_TRACK}, {@code ownerId} =
 * caller, message "Carbon target created.") with two Java-side hardenings:
 * both periods are tenant-validated ({@code 404 REPORTING_PERIOD_NOT_FOUND}
 * — Node never checked) and ranges are validated (400). PUT is greenfield,
 * justified by the frozen {@code targets.update} code: partial merge where
 * {@code null} means "leave unchanged", {@code notes} blank clears notes;
 * {@code id}/{@code organizationId}/{@code ownerId}/{@code createdAt} are
 * immutable. No DELETE exists (no {@code targets.delete} permission code).
 *
 * <p><b>Progress is computed, never stored</b> (ADR-018/019): the target
 * period's persisted ACTIVE records aggregated per basis; the two progress
 * percentages are {@code null} while the period has no records
 * ({@code hasPersistedEmissions = false}) or the planned reduction
 * {@code (baseline − target)} is not positive — reported as undefined rather
 * than guessed. Status is stored/user-set and is never rewritten from dates
 * or totals (no auto-ACHIEVED).
 */
@Service
public class CarbonTargetService {

    private static final int VALUE_SCALE = 4;
    private static final int PCT_SCALE = 2;
    private static final int MAX_NAME = 255;
    private static final Set<String> STATUSES =
            Set.of("ON_TRACK", "BEHIND", "ACHIEVED", "EXPIRED");

    private final CarbonTargetRepository targets;
    private final EmissionRecordRepository emissions;
    private final ScopeService scope;

    public CarbonTargetService(CarbonTargetRepository targets,
                               EmissionRecordRepository emissions,
                               ScopeService scope) {
        this.targets = targets;
        this.emissions = emissions;
        this.scope = scope;
    }

    public List<CarbonTargetDto> list(String organizationId) {
        List<CarbonTargetDto> result = new ArrayList<>();
        for (CarbonTarget target : targets.list(organizationId)) {
            result.add(withProgress(organizationId, target));
        }
        return result;
    }

    public CarbonTargetDto create(String organizationId, String userId,
                                  CarbonTargetRequest request) {
        if (request == null) {
            throw validation("Target payload is required.");
        }
        String name = requireName(request.getName());
        String baselinePeriodId =
                requireText(request.getBaselinePeriodId(), "baselinePeriodId is required.");
        String targetPeriodId =
                requireText(request.getTargetPeriodId(), "targetPeriodId is required.");
        BigDecimal baseline = requireNonNegative(request.getBaselineValueT(),
                "baselineValueT is required.");
        BigDecimal targetValue = requireNonNegative(request.getTargetValueT(),
                "targetValueT is required.");
        BigDecimal percentage = requirePercentage(request.getReductionPercentage());
        // Tenant validation (deviation from Node, which never checked).
        scope.requireReportingPeriod(organizationId, baselinePeriodId);
        scope.requireReportingPeriod(organizationId, targetPeriodId);

        CarbonTarget created = targets.insert(organizationId, name, baselinePeriodId,
                targetPeriodId, baseline, targetValue, percentage,
                "ON_TRACK", userId, normalizeNotes(request.getNotes()));
        return withProgress(organizationId, created);
    }

    /** Partial update: {@code null} = unchanged; blank {@code notes} clears. */
    public CarbonTargetDto update(String organizationId, String targetId,
                                  CarbonTargetRequest request) {
        String id = ScopeService.requireUuid(targetId, "Carbon target");
        CarbonTarget existing = targets.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Carbon target"));
        if (request == null) {
            throw validation("Target payload is required.");
        }

        String name = request.getName() == null
                ? existing.getName() : requireName(request.getName());
        String baselinePeriodId = request.getBaselinePeriodId() == null
                ? existing.getBaselinePeriodId()
                : requireText(request.getBaselinePeriodId(), "baselinePeriodId is required.");
        String targetPeriodId = request.getTargetPeriodId() == null
                ? existing.getTargetPeriodId()
                : requireText(request.getTargetPeriodId(), "targetPeriodId is required.");
        BigDecimal baseline = request.getBaselineValueT() == null
                ? existing.getBaselineValueT()
                : requireNonNegative(request.getBaselineValueT(),
                        "baselineValueT must be zero or greater.");
        BigDecimal targetValue = request.getTargetValueT() == null
                ? existing.getTargetValueT()
                : requireNonNegative(request.getTargetValueT(),
                        "targetValueT must be zero or greater.");
        BigDecimal percentage = request.getReductionPercentage() == null
                ? existing.getReductionPercentage()
                : requirePercentage(request.getReductionPercentage());
        String status = request.getStatus() == null
                ? existing.getStatus() : requireStatus(request.getStatus());
        String notes = request.getNotes() == null
                ? existing.getNotes() : normalizeNotes(request.getNotes());

        if (request.getBaselinePeriodId() != null) {
            scope.requireReportingPeriod(organizationId, baselinePeriodId);
        }
        if (request.getTargetPeriodId() != null) {
            scope.requireReportingPeriod(organizationId, targetPeriodId);
        }

        int updated = targets.update(organizationId, id, name, baselinePeriodId,
                targetPeriodId, baseline, targetValue, percentage, status, notes);
        if (updated != 1) {
            throw ScopeService.notFound("Carbon target");
        }
        CarbonTarget reloaded = targets.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Carbon target"));
        return withProgress(organizationId, reloaded);
    }

    // ------------------------------------------------------------------
    // Progress — computed from persisted records, never stored
    // ------------------------------------------------------------------

    private CarbonTargetDto withProgress(String organizationId, CarbonTarget target) {
        CarbonTargetDto dto = CarbonTargetDto.from(target);
        BigDecimal baseline = target.getBaselineValueT();
        BigDecimal planned = baseline.subtract(target.getTargetValueT());
        dto.setPlannedReductionT(planned.setScale(VALUE_SCALE, RoundingMode.HALF_UP));

        List<EmissionRecord> records =
                emissions.list(organizationId, target.getTargetPeriodId(), null, "ACTIVE", null);
        if (records.isEmpty()) {
            // No persisted rows: report absence instead of inventing progress.
            dto.setHasPersistedEmissions(false);
            return dto;
        }
        dto.setHasPersistedEmissions(true);

        AnalyticsService.Basis basis = new AnalyticsService.Basis();
        records.forEach(basis::add);
        dto.setCurrentLocationBasedT(basis.locationBasedTotal()
                .setScale(VALUE_SCALE, RoundingMode.HALF_UP));
        dto.setCurrentMarketBasedT(basis.marketBasedTotal()
                .setScale(VALUE_SCALE, RoundingMode.HALF_UP));

        if (planned.signum() > 0) {
            dto.setProgressLocationPct(
                    progressPct(baseline, basis.locationBasedTotal(), planned));
            dto.setProgressMarketPct(
                    progressPct(baseline, basis.marketBasedTotal(), planned));
        }
        // planned <= 0 → both pcts stay null (division undefined, reported not guessed)
        return dto;
    }

    /** {@code (baseline − current) / planned × 100}, 2dp HALF_UP. */
    private static BigDecimal progressPct(BigDecimal baseline, BigDecimal current,
                                          BigDecimal planned) {
        return baseline.subtract(current)
                .multiply(BigDecimal.valueOf(100))
                .divide(planned, PCT_SCALE, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private static AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private static String requireName(String name) {
        String value = requireText(name, "Target name is required.");
        if (value.length() > MAX_NAME) {
            throw validation("Target name must be " + MAX_NAME + " characters or fewer.");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String missingMessage) {
        if (value == null) {
            throw validation(missingMessage);
        }
        if (value.signum() < 0) {
            throw validation("Target values must be zero or greater.");
        }
        return value;
    }

    private static BigDecimal requirePercentage(BigDecimal value) {
        if (value == null) {
            throw validation("reductionPercentage is required.");
        }
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw validation("reductionPercentage must be between 0 and 100.");
        }
        return value;
    }

    private static String requireStatus(String status) {
        String value = status.trim();
        if (!STATUSES.contains(value)) {
            throw validation("Target status must be one of: ON_TRACK, BEHIND, "
                    + "ACHIEVED, EXPIRED.");
        }
        return value;
    }

    private static String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
