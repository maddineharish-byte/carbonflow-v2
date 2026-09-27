package com.carbonflow.service;

import com.carbonflow.dto.AccountingResponses;
import com.carbonflow.dto.BatchCalculationRequest;
import com.carbonflow.dto.CalculationRequest;
import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.GwpSet;
import com.carbonflow.model.GwpValue;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.CalculationRepository;
import com.carbonflow.repository.EmissionFactorRepository;
import com.carbonflow.repository.GwpSetRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Canonical calculation module (Phase 6): {@code POST /calculations/run},
 * {@code POST /calculations/batch-run} and the greenfield
 * {@code GET /calculations/:id}, ported from the Node reference's production
 * contract ({@code server/routes.ts}).
 *
 * <p><b>Canonical flow position:</b> this service resolves references (factor
 * selection + GWP set), hands the activity to the pure
 * {@link GhgCalculationEngine} (unit normalization → gas-level results →
 * totals → snapshot → Scope 2 classification), then persists through
 * {@link CalculationPersistence}. Reads run outside the write transaction —
 * the same boundary Node has ({@code resolveCalculationReferences} … then
 * {@code persistCalculation} {@code BEGIN}).
 *
 * <p><b>Error contract (Node run handler, exact):</b>
 * <ul>
 *   <li>400 {@code VALIDATION_ERROR} {@code activityDataId is required.} —
 *       missing or non-string field;</li>
 *   <li>400 {@code VALIDATION_ERROR} {@code Calculation input is invalid.} —
 *       malformed uuid (Node's {@code assertUuid} inside the run handler's
 *       try);</li>
 *   <li>404 {@code ACTIVITY_NOT_FOUND} {@code Activity data not found.} —
 *       unknown or cross-tenant;</li>
 *   <li>400 {@code FACTOR_NOT_FOUND} {@code No active emission factor found
 *       for activity.} · 400 {@code GWP_SET_NOT_FOUND} {@code The selected GWP
 *       set is unavailable.};</li>
 *   <li>400 {@code INVALID_CALCULATION_RELATIONSHIP} — persistence-phase
 *       reference failures (batch rewords it to Node's batch sentence);</li>
 *   <li>503 {@code CALCULATION_PERSISTENCE_UNAVAILABLE} — any other storage
 *       failure (Node's catch-all fall-through).</li>
 * </ul>
 *
 * <p><b>Documented deviations (ADR-017):</b> 409 {@code AUDIT_LOCKED} when the
 * reporting period is frozen (checked after the 404, before resolution); the
 * engine raises a specific {@code VALIDATION_ERROR} for an uncovered unit
 * pair instead of Node's silent factor-of-1 fallback; a GWP set missing a gas
 * value reports the gas instead of Node's hardcoded CO2/CH4/N2O defaults.
 */
@Service
public class CalculationService {

    private final ActivityDataRepository activities;
    private final EmissionFactorRepository factors;
    private final GwpSetRepository gwpSets;
    private final CalculationRepository calculations;
    private final AccountingLockGuard locks;
    private final GhgCalculationEngine engine;
    private final CalculationPersistence persistence;

    public CalculationService(ActivityDataRepository activities,
                              EmissionFactorRepository factors,
                              GwpSetRepository gwpSets,
                              CalculationRepository calculations,
                              AccountingLockGuard locks,
                              GhgCalculationEngine engine,
                              CalculationPersistence persistence) {
        this.activities = activities;
        this.factors = factors;
        this.gwpSets = gwpSets;
        this.calculations = calculations;
        this.locks = locks;
        this.engine = engine;
        this.persistence = persistence;
    }

    // ==========================================
    // POST /calculations/run
    // ==========================================

    /**
     * Node's {@code POST /calculations/run} (production path). One
     * deterministic execution: resolve → execute → persist atomically.
     */
    public AccountingResponses.CalculationRunResult run(String organizationId, String userId,
                                                        CalculationRequest request) {
        Object rawId = request == null ? null : request.getActivityDataId();
        if (!(rawId instanceof String activityDataId) || activityDataId.isEmpty()) {
            throw validation("activityDataId is required.");
        }
        if (!UuidContract.isNodeUuid(activityDataId)) {
            throw validation("Calculation input is invalid.");
        }

        ActivityData activity;
        try {
            activity = activities.findById(organizationId, activityDataId)
                    .orElseThrow(CalculationService::notFound);
        } catch (DataAccessException e) {
            throw persistenceUnavailable();
        }

        try {
            // Deviation (ADR-017): audited history is frozen — Node has no
            // lock concept here. Checked after the tenant-scoped 404, before
            // reference resolution.
            locks.requireUnlockedPeriod(organizationId, activity.getReportingPeriodId());

            String factorVersionId = CalculationRequest.optionalString(request.getFactorVersionId());
            String gwpSetId = CalculationRequest.optionalString(request.getGwpSetId());
            if (factorVersionId != null && !UuidContract.isNodeUuid(factorVersionId)) {
                throw validation("Calculation input is invalid.");
            }
            if (gwpSetId != null && !UuidContract.isNodeUuid(gwpSetId)) {
                throw validation("Calculation input is invalid.");
            }
            GhgCalculationEngine.ResolvedReferences references =
                    resolveReferences(activity, factorVersionId, gwpSetId);
            GhgCalculationEngine.CalculationOutput output =
                    engine.executeCalculation(activity, references, userId);
            return persistence.persist(organizationId, userId, activity, output);
        } catch (DataAccessException e) {
            throw persistenceUnavailable();
        }
    }

    // ==========================================
    // POST /calculations/batch-run
    // ==========================================

    /**
     * Node's {@code POST /calculations/batch-run}: every activity of the
     * organization (optionally one period) is an independent attempt —
     * unresolvable references skip the item, anything else aborts the batch
     * with Node's batch wording. Each item persists in its own transaction
     * (Node's per-item {@code BEGIN/COMMIT}), so earlier successes survive an
     * aborted batch exactly like the reference.
     *
     * <p>Skips: {@code FACTOR_NOT_FOUND} / {@code GWP_SET_NOT_FOUND} (Node),
     * plus the documented deviations — activities in a locked reporting period
     * and activities whose unit pair is not in the conversion table.
     */
    public AccountingResponses.BatchCalculationResult batchRun(String organizationId, String userId,
                                                               BatchCalculationRequest request) {
        Object rawPeriod = request == null ? null : request.getReportingPeriodId();
        if (rawPeriod != null && !(rawPeriod instanceof String)) {
            throw validation("reportingPeriodId must be a string.");
        }
        String periodId = CalculationRequest.optionalString(rawPeriod);

        List<ActivityData> batch;
        try {
            if (periodId != null && !UuidContract.isNodeUuid(periodId)) {
                throw validation("Batch calculation input is invalid.");
            }
            batch = activities.list(organizationId, periodId, null, null);
        } catch (AuthException e) {
            throw e;
        } catch (DataAccessException e) {
            throw persistenceUnavailable();
        }

        int processed = 0;
        for (ActivityData activity : batch) {
            try {
                // Deviation (ADR-017): frozen periods are reported as
                // unprocessed instead of mutating audited history.
                if (locks.isLocked(organizationId, activity.getReportingPeriodId())) {
                    continue;
                }
                GhgCalculationEngine.ResolvedReferences references =
                        resolveReferences(activity, null, null);
                GhgCalculationEngine.CalculationOutput output =
                        engine.executeCalculation(activity, references, userId);
                persistence.persist(organizationId, userId, activity, output);
                processed++;
            } catch (UnitConversionService.UnsupportedUnitConversionException e) {
                // Deviation (ADR-017): batch skips units the table cannot
                // normalize (Node silently multiplies by 1 instead).
                continue;
            } catch (AuthException e) {
                switch (e.getCode()) {
                    case "FACTOR_NOT_FOUND", "GWP_SET_NOT_FOUND", "AUDIT_LOCKED" -> {
                        continue;
                    }
                    case "INVALID_CALCULATION_RELATIONSHIP", "INVALID_CALCULATION_REFERENCE" ->
                            throw new AuthException("INVALID_CALCULATION_RELATIONSHIP",
                                    "A calculation reference is invalid for this organization.",
                                    HttpStatus.BAD_REQUEST);
                    case "VALIDATION_ERROR" ->
                            throw validation("Batch calculation input is invalid.");
                    default -> throw persistenceUnavailable();
                }
            } catch (DataAccessException e) {
                throw persistenceUnavailable();
            }
        }
        return new AccountingResponses.BatchCalculationResult(processed, batch.size());
    }

    // ==========================================
    // GET /calculations/:id (greenfield)
    // ==========================================

    /**
     * Greenfield audit-snapshot read (API.md §2.5; Node's {@code getCalculation}
     * exists but is not routed). Malformed, unknown and cross-tenant ids
     * collapse into the Phase 3/4 anti-enumeration 404.
     */
    public Calculation getCalculation(String organizationId, String calculationId) {
        if (calculationId == null || calculationId.isBlank()
                || !UuidContract.isNodeUuid(calculationId)) {
            throw calculationNotFound();
        }
        try {
            return calculations.findById(organizationId, calculationId)
                    .orElseThrow(CalculationService::calculationNotFound);
        } catch (DataAccessException e) {
            throw persistenceUnavailable();
        }
    }

    // ==========================================
    // Reference resolution (Node's resolveCalculationReferences)
    // ==========================================

    /**
     * Resolves the ACTIVE factor version for the activity's activity type
     * (optionally pinned) and the GWP set (explicit id or the platform
     * default), then loads the set's gas values.
     *
     * <p>Node asserts the uuids before querying — malformed explicit ids are
     * {@code VALIDATION_ERROR} here too (validated by the caller), so an empty
     * result always means "no such reference", never "bad input".
     */
    private GhgCalculationEngine.ResolvedReferences resolveReferences(
            ActivityData activity, String factorVersionId, String gwpSetId) {
        EmissionFactorRepository.ResolvedVersion resolved =
                factors.resolveActiveVersion(activity.getActivityType(), factorVersionId)
                        .orElseThrow(CalculationService::factorNotFound);
        GwpSet set = gwpSets.resolve(gwpSetId)
                .orElseThrow(CalculationService::gwpNotFound);
        List<GwpValue> values = gwpSets.values(set.getId());
        return new GhgCalculationEngine.ResolvedReferences(
                resolved.version(), resolved.inputUnit(), set, values);
    }

    // ==========================================
    // Contract errors (Node wording)
    // ==========================================

    private static AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private static AuthException notFound() {
        return new AuthException("ACTIVITY_NOT_FOUND", "Activity data not found.",
                HttpStatus.NOT_FOUND);
    }

    private static AuthException calculationNotFound() {
        return new AuthException("CALCULATION_NOT_FOUND",
                "Calculation does not exist or access denied.", HttpStatus.NOT_FOUND);
    }

    private static AuthException factorNotFound() {
        return new AuthException("FACTOR_NOT_FOUND",
                "No active emission factor found for activity.", HttpStatus.BAD_REQUEST);
    }

    private static AuthException gwpNotFound() {
        return new AuthException("GWP_SET_NOT_FOUND",
                "The selected GWP set is unavailable.", HttpStatus.BAD_REQUEST);
    }

    private static AuthException persistenceUnavailable() {
        return new AuthException("CALCULATION_PERSISTENCE_UNAVAILABLE",
                "Calculation persistence is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
