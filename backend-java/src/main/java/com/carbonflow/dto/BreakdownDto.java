package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dimension breakdown ({@code GET /analytics/breakdown}, Phase 7 Workstream A;
 * fulfils the API.md §2.9 contract that neither Java nor Node had
 * implemented).
 *
 * <p>Dimensions: {@code facility}, {@code legal_entity}, {@code scope},
 * {@code category}, {@code period}. Rows carry both Scope 2 perspectives
 * side by side — {@code totalLocationBasedTonnes} = Scope 1 + Scope 2
 * location + Scope 3, {@code totalMarketBasedTonnes} = Scope 1 + Scope 2
 * market + Scope 3. The two perspectives are never added together (ADR-002);
 * unlike the Node oracle's facility/category dashboard breakdown, no row here
 * double-counts dual-reported rows (ADR-018).
 *
 * <p>Tonnes are presentation-rounded to 2dp HALF_UP after full-precision
 * aggregation; row order is deterministic per dimension (facility/legal entity
 * by name, scope in scope order, category by tonnes desc, period by start
 * date), so two identical databases always produce identical payloads.
 */
public class BreakdownDto {

    private String dimension;
    private String periodId;
    private List<Row> rows;

    /** One aggregated row of the requested dimension. */
    public static class Row {
        public String key;
        public String label;
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal scope3Tonnes = BigDecimal.ZERO;
        public BigDecimal totalLocationBasedTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;

        public Row() {}

        public Row(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }

    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
}
