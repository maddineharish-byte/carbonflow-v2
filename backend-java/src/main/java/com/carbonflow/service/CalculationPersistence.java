package com.carbonflow.service;

import com.carbonflow.dto.AccountingResponses;
import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.EmissionFactorVersion;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.GwpSet;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.CalculationRepository;
import com.carbonflow.repository.EmissionFactorRepository;
import com.carbonflow.repository.EmissionRecordRepository;
import com.carbonflow.repository.GwpSetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a calculation run — a port of Node's
 * {@code persistCalculation} ({@code server/calculation-repository.ts}),
 * executed as one transaction so the ledger can never show a half-written run:
 *
 * <ol>
 *   <li>lock the activity row {@code FOR SHARE} and verify its tenant anchors
 *       (period, facility, scope, category) have not moved since the engine
 *       read them — {@code INVALID_CALCULATION_RELATIONSHIP};</li>
 *   <li>re-read the pinned factor version (must still be {@code ACTIVE}) and
 *       the GWP set — {@code INVALID_CALCULATION_REFERENCE};</li>
 *   <li>verify the snapshot the engine took still matches those rows (factor
 *       unit/version/value/source + GWP set name) — Node raises
 *       {@code INVALID_CALCULATION_SNAPSHOT}, which its run/batch handlers do
 *       not special-case and therefore surface as {@code 503
 *       CALCULATION_PERSISTENCE_UNAVAILABLE}. Java emits that outcome
 *       directly;</li>
 *   <li>supersede the activity's previous {@code ACTIVE} emission records,
 *       insert the calculation + gas results + the new emission record, flip
 *       the activity to {@code CALCULATED};</li>
 *   <li>{@code 23503} from any composite foreign key (V5/V6) collapses to
 *       {@code INVALID_CALCULATION_RELATIONSHIP}, exactly like the reference;
 *       every other storage failure leaves this method as a
 *       {@code DataAccessException} and the endpoint maps it to its
 *       domain-specific 503.</li>
 * </ol>
 *
 * <p><b>Why this is its own bean:</b> {@code @Transactional} only applies
 * across bean boundaries. {@code CalculationService.batchRun} deliberately
 * holds no transaction (Node gives every batch item its own
 * {@code BEGIN/COMMIT}), so it must call this bean per activity — one
 * transaction per item, partial progress preserved on failure, like the
 * reference. A single run calls it inside its own flow with the same effect:
 * exactly one transaction for the write phase (the reads before it run
 * outside, as they do in Node).
 *
 * <p><b>Tenant isolation:</b> every statement carries
 * {@code organization_id = ?} from the authenticated context; the V5/V6
 * composite foreign keys are the database backstop (ADR-015).
 */
@Service
public class CalculationPersistence {

    private final ActivityDataRepository activities;
    private final EmissionFactorRepository factors;
    private final GwpSetRepository gwpSets;
    private final CalculationRepository calculations;
    private final EmissionRecordRepository emissions;

    public CalculationPersistence(ActivityDataRepository activities,
                                  EmissionFactorRepository factors,
                                  GwpSetRepository gwpSets,
                                  CalculationRepository calculations,
                                  EmissionRecordRepository emissions) {
        this.activities = activities;
        this.factors = factors;
        this.gwpSets = gwpSets;
        this.calculations = calculations;
        this.emissions = emissions;
    }

    /**
     * Writes one execution's snapshot + ledger row atomically.
     *
     * @return the same {@code {calculation, emissionRecord}} pair Node returns
     * @throws AuthException {@code INVALID_CALCULATION_RELATIONSHIP} /
     *                        {@code INVALID_CALCULATION_REFERENCE} (400,
     *                        mapped by each endpoint to its exact wording) or
     *                        {@code CALCULATION_PERSISTENCE_UNAVAILABLE} (503)
     */
    @Transactional
    public AccountingResponses.CalculationRunResult persist(
            String organizationId, String userId, ActivityData activity,
            GhgCalculationEngine.CalculationOutput output) {
        Calculation calculation = output.calculation();
        EmissionRecord record = output.emissionRecord();

        // Node's pre-write assertions (org/id linkage is true by construction
        // for engine output; the statement-level ones are re-checked below).
        if (!organizationId.equals(activity.getOrganizationId())
                || !organizationId.equals(calculation.getOrganizationId())
                || !organizationId.equals(record.getOrganizationId())
                || !activity.getId().equals(calculation.getActivityDataId())
                || !activity.getReportingPeriodId().equals(calculation.getReportingPeriodId())
                || !calculation.getId().equals(record.getCalculationId())
                || !activity.getReportingPeriodId().equals(record.getReportingPeriodId())
                || !activity.getFacilityId().equals(record.getFacilityId())
                || activity.getScope() != record.getScope()
                || !activity.getCategory().equals(record.getCategory())
                || !"ACTIVE".equals(record.getStatus())) {
            throw relationship();
        }

        // Row lock: the activity must still exist for this tenant with the
        // same anchors the engine snapshotted.
        ActivityData locked = activities.lockForShare(organizationId, activity.getId())
                .orElseThrow(CalculationPersistence::relationship);
        if (!activity.getReportingPeriodId().equals(locked.getReportingPeriodId())
                || !activity.getFacilityId().equals(locked.getFacilityId())
                || activity.getScope() != locked.getScope()
                || !activity.getCategory().equals(locked.getCategory())) {
            throw relationship();
        }

        // Snapshot verification: reference data must not have changed between
        // execution and this transaction.
        EmissionFactorVersion current = factors.findActiveVersion(calculation.getFactorVersionId())
                .orElseThrow(CalculationPersistence::reference);
        GwpSet currentSet = gwpSets.findById(calculation.getGwpSetId())
                .orElseThrow(CalculationPersistence::reference);
        String currentSource = current.getSource() + " (" + current.getSourceYear() + ")";
        if (!current.getFactorUnit().equals(calculation.getFactorUnit())
                || current.getVersionNumber() != calculation.getFactorVersion()
                || current.getCo2eFactor().compareTo(calculation.getFactorValue()) != 0
                || !currentSource.equals(calculation.getFactorSource())
                || !currentSet.getName().equals(calculation.getGwpName())) {
            // Node: INVALID_CALCULATION_SNAPSHOT falls through its catch → 503.
            throw new AuthException("CALCULATION_PERSISTENCE_UNAVAILABLE",
                    "Calculation persistence is temporarily unavailable.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        try {
            emissions.supersedeActiveForActivity(organizationId, activity.getId());
            calculations.insert(organizationId, userId, calculation);
            calculations.insertGasResults(calculation);
            emissions.insert(organizationId, record);
            activities.updateStatus(organizationId, activity.getId(), "CALCULATED");
        } catch (DataIntegrityViolationException e) {
            // Node: 23503 → INVALID_CALCULATION_RELATIONSHIP (ROLLBACK follows).
            throw relationship();
        }
        // Any other DataAccessException propagates; each endpoint maps it to
        // its domain-specific 503 (Node's unrecognized-error fall-through).
        return new AccountingResponses.CalculationRunResult(calculation, record);
    }

    private static AuthException relationship() {
        return new AuthException("INVALID_CALCULATION_RELATIONSHIP",
                "Calculation references are invalid for this organization.",
                HttpStatus.BAD_REQUEST);
    }

    private static AuthException reference() {
        return new AuthException("INVALID_CALCULATION_REFERENCE",
                "Calculation references are invalid for this organization.",
                HttpStatus.BAD_REQUEST);
    }
}
