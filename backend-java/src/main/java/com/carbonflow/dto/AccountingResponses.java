package com.carbonflow.dto;

import com.carbonflow.model.Calculation;
import com.carbonflow.model.EmissionRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 6 accounting payloads: calculation run/batch results and the emission
 * ledger response.
 *
 * <p>Shapes mirror the Node reference exactly: {@code POST /calculations/run}
 * answers {@code {calculation, emissionRecord}}, batch-run answers
 * {@code {processed, total}} and {@code GET /emissions} answers
 * {@code {records, summary}} with the five four-decimal totals —
 * location- and market-based perspectives reported separately and never
 * summed together.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AccountingResponses {

    private AccountingResponses() {
    }

    public static class CalculationRunResult {
        public Calculation calculation;
        public EmissionRecord emissionRecord;

        public CalculationRunResult() {
        }

        public CalculationRunResult(Calculation calculation, EmissionRecord emissionRecord) {
            this.calculation = calculation;
            this.emissionRecord = emissionRecord;
        }
    }

    public static class BatchCalculationResult {
        public int processed;
        public int total;

        public BatchCalculationResult() {
        }

        public BatchCalculationResult(int processed, int total) {
            this.processed = processed;
            this.total = total;
        }
    }

    /**
     * Scope 2 dual-reporting summary: {@code totalLocationBasedTonnes} is
     * {@code scope1 + scope2Location} and {@code totalMarketBasedTonnes} is
     * {@code scope1 + scope2Market}. The two perspectives are never added
     * together — there is deliberately no combined Scope 2 total.
     */
    public static class EmissionsSummary {
        // Node: Number(x.toFixed(4)) — four-decimal rounding, trailing zeros
        // stripped on the wire (0 not 0.0000). Half-up applied after summing.
        @JsonSerialize(using = PlainBigDecimalSerializer.class)
        public java.math.BigDecimal scope1Tonnes = java.math.BigDecimal.ZERO;
        @JsonSerialize(using = PlainBigDecimalSerializer.class)
        public java.math.BigDecimal scope2LocationTonnes = java.math.BigDecimal.ZERO;
        @JsonSerialize(using = PlainBigDecimalSerializer.class)
        public java.math.BigDecimal scope2MarketTonnes = java.math.BigDecimal.ZERO;
        @JsonSerialize(using = PlainBigDecimalSerializer.class)
        public java.math.BigDecimal totalLocationBasedTonnes = java.math.BigDecimal.ZERO;
        @JsonSerialize(using = PlainBigDecimalSerializer.class)
        public java.math.BigDecimal totalMarketBasedTonnes = java.math.BigDecimal.ZERO;
    }

    public static class EmissionLedgerResult {
        public java.util.List<EmissionRecord> records;
        public EmissionsSummary summary;

        public EmissionLedgerResult() {
        }

        public EmissionLedgerResult(java.util.List<EmissionRecord> records, EmissionsSummary summary) {
            this.records = records;
            this.summary = summary;
        }
    }
}
