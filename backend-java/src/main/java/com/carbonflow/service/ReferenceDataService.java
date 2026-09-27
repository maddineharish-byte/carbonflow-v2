package com.carbonflow.service;

import com.carbonflow.model.CalculationMethodology;
import com.carbonflow.model.EmissionFactor;
import com.carbonflow.model.GwpSet;
import com.carbonflow.repository.CalculationMethodologyRepository;
import com.carbonflow.repository.EmissionFactorRepository;
import com.carbonflow.repository.GwpSetRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reference reads (Phase 6): {@code GET /reference/gwp-sets}, {@code GET
 * /reference/emission-factors} — ported from the Node reference's production
 * handlers ({@code server/routes.ts}), plus the greenfield methodology read
 * (see below).
 *
 * <p>All three answer {@code 503 REFERENCE_PERSISTENCE_UNAVAILABLE}
 * {@code Reference persistence is temporarily unavailable.} on storage
 * failure — Node's exact catch for both routed reference endpoints.
 *
 * <p>GWP sets and factor library are platform-global reference data (neither
 * table carries {@code organization_id}); results keep Node's ordering —
 * sets: default first, newest publication year, then code; factors: activity
 * type, then fuel/activity; versions by number.
 *
 * <p><b>Greenfield (documented in ADR-017):</b> {@code calculation_methodologies}
 * is reference-only — no Node route and no methodology field on calculations
 * (ADR-008) — so the methodology read is additive and exposes the table as
 * lookup data only.
 */
@Service
public class ReferenceDataService {

    private final GwpSetRepository gwpSets;
    private final EmissionFactorRepository factors;
    private final CalculationMethodologyRepository methodologies;

    public ReferenceDataService(GwpSetRepository gwpSets,
                               EmissionFactorRepository factors,
                               CalculationMethodologyRepository methodologies) {
        this.gwpSets = gwpSets;
        this.factors = factors;
        this.methodologies = methodologies;
    }

    /** Every GWP set with its gas values (Node's {@code listGwpSetsWithValues}). */
    public List<GwpSet> gwpSets() {
        try {
            return gwpSets.listWithValues();
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    /** Every factor with its versions (Node's {@code listEmissionFactorsWithVersions}). */
    public List<EmissionFactor> emissionFactors() {
        try {
            return factors.listWithVersions();
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    /** Greenfield methodology lookup (no calculation references this table). */
    public List<CalculationMethodology> methodologies() {
        try {
            return methodologies.list();
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private static AuthException unavailable() {
        return new AuthException("REFERENCE_PERSISTENCE_UNAVAILABLE",
                "Reference persistence is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
