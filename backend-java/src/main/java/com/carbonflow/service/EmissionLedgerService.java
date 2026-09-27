package com.carbonflow.service;

import com.carbonflow.dto.AccountingResponses;
import com.carbonflow.dto.AccountingResponses.EmissionLedgerResult;
import com.carbonflow.dto.AccountingResponses.EmissionsSummary;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;
import com.carbonflow.repository.EmissionRecordRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Emission ledger module (Phase 6): {@code GET /emissions} — ported from the
 * Node reference's production handler ({@code server/routes.ts}).
 *
 * <p><b>Canonical flow position:</b> the ledger is the end of the chain
 * (activity data → validation → factors → normalization → calculation → gas
 * results → emission records). Only {@code ACTIVE} records are listed;
 * superseded rows stay in the table for audit history.
 *
 * <p><b>Scope 2 dual reporting (ADR-002):</b> the summary reports
 * location-based and market-based totals as separate perspectives plus two
 * separate Scope 1 + Scope 2 totals. There is deliberately no combined Scope
 * 2 number and no query ever sums the two perspectives together.
 *
 * <p><b>Error contract (Node):</b> malformed period filter → 400
 * {@code VALIDATION_ERROR} {@code Emission filter is invalid.}; storage
 * failure → 503 {@code EMISSION_PERSISTENCE_UNAVAILABLE} {@code Emission
 * persistence is temporarily unavailable.} Non-string filters cannot occur
 * here (query parameters are always strings); an empty filter string means
 * "no filter", exactly like Node's truthiness check.
 */
@Service
public class EmissionLedgerService {

    /** Four-decimal HALF_UP, applied after summing (Node: toFixed(4)). */
    private static final int SUMMARY_SCALE = 4;

    private final EmissionRecordRepository emissions;

    public EmissionLedgerService(EmissionRecordRepository emissions) {
        this.emissions = emissions;
    }

    /** {@code GET /emissions}: active records for the tenant + summary. */
    public EmissionLedgerResult list(String organizationId, String periodId) {
        String filter = (periodId == null || periodId.isEmpty()) ? null : periodId;
        try {
            if (filter != null && !UuidContract.isNodeUuid(filter)) {
                throw new AuthException("VALIDATION_ERROR", "Emission filter is invalid.",
                        HttpStatus.BAD_REQUEST);
            }
            List<EmissionRecord> records =
                    emissions.list(organizationId, filter, null, "ACTIVE", null);
            return new EmissionLedgerResult(records, summarize(records));
        } catch (AuthException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new AuthException("EMISSION_PERSISTENCE_UNAVAILABLE",
                    "Emission persistence is temporarily unavailable.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Sums per perspective first, rounds once at the end. Tolerance-free
     * BigDecimal accumulation — no float, no intermediate rounding (the
     * records themselves carry the schema's 6 dp).
     */
    static EmissionsSummary summarize(List<EmissionRecord> records) {
        BigDecimal scope1 = BigDecimal.ZERO;
        BigDecimal scope2Location = BigDecimal.ZERO;
        BigDecimal scope2Market = BigDecimal.ZERO;

        for (EmissionRecord record : records) {
            if (record.getCo2eTonnes() == null) {
                continue;
            }
            if (record.getScope() == GHGScope.SCOPE_1) {
                scope1 = scope1.add(record.getCo2eTonnes());
            } else if (record.getScope() == GHGScope.SCOPE_2) {
                // Only classified rows enter a perspective; a null
                // scope2Type would be a contract violation, never summed
                // into either total.
                if (record.getScope2Type() == Scope2Method.LOCATION_BASED) {
                    scope2Location = scope2Location.add(record.getCo2eTonnes());
                } else if (record.getScope2Type() == Scope2Method.MARKET_BASED) {
                    scope2Market = scope2Market.add(record.getCo2eTonnes());
                }
            }
        }

        EmissionsSummary summary = new EmissionsSummary();
        summary.scope1Tonnes = scope1.setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
        summary.scope2LocationTonnes = scope2Location.setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
        summary.scope2MarketTonnes = scope2Market.setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
        summary.totalLocationBasedTonnes =
                scope1.add(scope2Location).setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
        summary.totalMarketBasedTonnes =
                scope1.add(scope2Market).setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
        return summary;
    }
}
