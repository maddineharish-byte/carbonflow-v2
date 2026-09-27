package com.carbonflow.model;

import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code calculations} row in the Node reference's wire shape
 * ({@code mapCalculation} in {@code server/calculation-repository.ts}) — an
 * immutable snapshot of everything the engine used at execution time:
 *
 * <ul>
 *   <li>the original activity quantity/unit <em>and</em> the normalized
 *       quantity/unit plus the conversion factor applied;</li>
 *   <li>the resolved factor's value, unit, source string and version number
 *       (the V6 provenance columns, not a re-read of today's factor);</li>
 *   <li>the GWP set name, per-gas results, totals and the SHA-256 integrity
 *       checksum over the snapshot inputs.</li>
 * </ul>
 *
 * <p>{@code factorId} is an additive field (V6 {@code calculations.factor_id}
 * was always persisted but never echoed by Node's {@code CALCULATION_SELECT});
 * it is returned here so clients can show the factor identity without
 * re-deriving it from the version id.
 *
 * <p>{@code gasResults} is always present (empty array when none) — Node
 * returns {@code COALESCE(..., '[]')} for every calculation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Calculation {

    private String id;
    private String organizationId;
    private String activityDataId;
    private String reportingPeriodId;
    private String factorVersionId;
    private String gwpSetId;
    // Numeric fields serialize with trailing zeros stripped (Node writes JS
    // numbers: Number("100.0000") === 100) — see PlainBigDecimalSerializer.
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal originalQuantity;
    private String originalUnit;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal normalizedQuantity;
    private String normalizedUnit;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal conversionFactor;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal factorValue;
    private String factorUnit;
    private String factorSource;
    private int factorVersion;
    private String gwpName;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal totalCo2eKg;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal totalCo2eTonnes;
    private String calculationHash;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<CalculationGasResult> gasResults = new ArrayList<>();

    private Instant calculatedAt;
    private String calculatedBy;
    private String factorId;

    public Calculation() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getActivityDataId() { return activityDataId; }
    public void setActivityDataId(String activityDataId) { this.activityDataId = activityDataId; }

    public String getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

    public String getFactorVersionId() { return factorVersionId; }
    public void setFactorVersionId(String factorVersionId) { this.factorVersionId = factorVersionId; }

    public String getGwpSetId() { return gwpSetId; }
    public void setGwpSetId(String gwpSetId) { this.gwpSetId = gwpSetId; }

    public BigDecimal getOriginalQuantity() { return originalQuantity; }
    public void setOriginalQuantity(BigDecimal originalQuantity) { this.originalQuantity = originalQuantity; }

    public String getOriginalUnit() { return originalUnit; }
    public void setOriginalUnit(String originalUnit) { this.originalUnit = originalUnit; }

    public BigDecimal getNormalizedQuantity() { return normalizedQuantity; }
    public void setNormalizedQuantity(BigDecimal normalizedQuantity) { this.normalizedQuantity = normalizedQuantity; }

    public String getNormalizedUnit() { return normalizedUnit; }
    public void setNormalizedUnit(String normalizedUnit) { this.normalizedUnit = normalizedUnit; }

    public BigDecimal getConversionFactor() { return conversionFactor; }
    public void setConversionFactor(BigDecimal conversionFactor) { this.conversionFactor = conversionFactor; }

    public BigDecimal getFactorValue() { return factorValue; }
    public void setFactorValue(BigDecimal factorValue) { this.factorValue = factorValue; }

    public String getFactorUnit() { return factorUnit; }
    public void setFactorUnit(String factorUnit) { this.factorUnit = factorUnit; }

    public String getFactorSource() { return factorSource; }
    public void setFactorSource(String factorSource) { this.factorSource = factorSource; }

    public int getFactorVersion() { return factorVersion; }
    public void setFactorVersion(int factorVersion) { this.factorVersion = factorVersion; }

    public String getGwpName() { return gwpName; }
    public void setGwpName(String gwpName) { this.gwpName = gwpName; }

    public BigDecimal getTotalCo2eKg() { return totalCo2eKg; }
    public void setTotalCo2eKg(BigDecimal totalCo2eKg) { this.totalCo2eKg = totalCo2eKg; }

    public BigDecimal getTotalCo2eTonnes() { return totalCo2eTonnes; }
    public void setTotalCo2eTonnes(BigDecimal totalCo2eTonnes) { this.totalCo2eTonnes = totalCo2eTonnes; }

    public String getCalculationHash() { return calculationHash; }
    public void setCalculationHash(String calculationHash) { this.calculationHash = calculationHash; }

    public List<CalculationGasResult> getGasResults() { return gasResults; }
    public void setGasResults(List<CalculationGasResult> gasResults) {
        this.gasResults = gasResults == null ? new ArrayList<>() : gasResults;
    }

    public Instant getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(Instant calculatedAt) { this.calculatedAt = calculatedAt; }

    public String getCalculatedBy() { return calculatedBy; }
    public void setCalculatedBy(String calculatedBy) { this.calculatedBy = calculatedBy; }

    public String getFactorId() { return factorId; }
    public void setFactorId(String factorId) { this.factorId = factorId; }
}
