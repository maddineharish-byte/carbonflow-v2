package com.carbonflow.model;

import com.carbonflow.model.enums.GHGScope;

import java.math.BigDecimal;
import java.time.Instant;

public class Calculation {
    private String id;
    private String organizationId;
    private String activityDataId;
    private String emissionFactorId;
    private BigDecimal originalQuantity;
    private String originalUnit;
    private BigDecimal normalizedQuantity;
    private String normalizedUnit;
    private BigDecimal factorValue;
    private BigDecimal calculatedKgCO2e;
    private BigDecimal calculatedTonnesCO2e;
    private String formula;
    private String calculationHash; // SHA-256 tamper-evident calculation seal
    private String calculatedBy;
    private Instant calculatedAt;

    public Calculation() {}

    public Calculation(String id, String organizationId, String activityDataId, String emissionFactorId,
                       BigDecimal originalQuantity, String originalUnit, BigDecimal normalizedQuantity,
                       String normalizedUnit, BigDecimal factorValue, BigDecimal calculatedKgCO2e,
                       BigDecimal calculatedTonnesCO2e, String formula, String calculationHash,
                       String calculatedBy, Instant calculatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.activityDataId = activityDataId;
        this.emissionFactorId = emissionFactorId;
        this.originalQuantity = originalQuantity;
        this.originalUnit = originalUnit;
        this.normalizedQuantity = normalizedQuantity;
        this.normalizedUnit = normalizedUnit;
        this.factorValue = factorValue;
        this.calculatedKgCO2e = calculatedKgCO2e;
        this.calculatedTonnesCO2e = calculatedTonnesCO2e;
        this.formula = formula;
        this.calculationHash = calculationHash;
        this.calculatedBy = calculatedBy;
        this.calculatedAt = calculatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getActivityDataId() { return activityDataId; }
    public void setActivityDataId(String activityDataId) { this.activityDataId = activityDataId; }

    public String getEmissionFactorId() { return emissionFactorId; }
    public void setEmissionFactorId(String emissionFactorId) { this.emissionFactorId = emissionFactorId; }

    public BigDecimal getOriginalQuantity() { return originalQuantity; }
    public void setOriginalQuantity(BigDecimal originalQuantity) { this.originalQuantity = originalQuantity; }

    public String getOriginalUnit() { return originalUnit; }
    public void setOriginalUnit(String originalUnit) { this.originalUnit = originalUnit; }

    public BigDecimal getNormalizedQuantity() { return normalizedQuantity; }
    public void setNormalizedQuantity(BigDecimal normalizedQuantity) { this.normalizedQuantity = normalizedQuantity; }

    public String getNormalizedUnit() { return normalizedUnit; }
    public void setNormalizedUnit(String normalizedUnit) { this.normalizedUnit = normalizedUnit; }

    public BigDecimal getFactorValue() { return factorValue; }
    public void setFactorValue(BigDecimal factorValue) { this.factorValue = factorValue; }

    public BigDecimal getCalculatedKgCO2e() { return calculatedKgCO2e; }
    public void setCalculatedKgCO2e(BigDecimal calculatedKgCO2e) { this.calculatedKgCO2e = calculatedKgCO2e; }

    public BigDecimal getCalculatedTonnesCO2e() { return calculatedTonnesCO2e; }
    public void setCalculatedTonnesCO2e(BigDecimal calculatedTonnesCO2e) { this.calculatedTonnesCO2e = calculatedTonnesCO2e; }

    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }

    public String getCalculationHash() { return calculationHash; }
    public void setCalculationHash(String calculationHash) { this.calculationHash = calculationHash; }

    public String getCalculatedBy() { return calculatedBy; }
    public void setCalculatedBy(String calculatedBy) { this.calculatedBy = calculatedBy; }

    public Instant getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(Instant calculatedAt) { this.calculatedAt = calculatedAt; }
}
