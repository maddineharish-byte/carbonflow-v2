package com.carbonflow.model;

import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code emission_factor_versions} in Node's wire shape
 * ({@code mapFactorVersion}).
 *
 * <p>Gas-specific values ({@code co2Factor}/{@code ch4Factor}/{@code n2oFactor})
 * drive the gas-split engine path when any of them is positive;
 * {@code co2eFactor} is the single composite value used otherwise (and is the
 * value recorded as {@code calculation.factorValue} either way). Only one
 * version of a factor is {@code ACTIVE} at a time — selection picks the
 * highest {@code versionNumber} among ACTIVE versions for the activity type.
 *
 * <p>{@code effectiveEnd} is omitted when open-ended (Node drops the
 * {@code undefined} property).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmissionFactorVersion {

    private String id;
    private String emissionFactorId;
    private int versionNumber;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal co2Factor;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal ch4Factor;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal n2oFactor;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal co2eFactor;
    private String factorUnit;
    private String source;
    private int sourceYear;
    private String geography;
    private String status;
    private LocalDate effectiveStart;
    private LocalDate effectiveEnd;

    public EmissionFactorVersion() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmissionFactorId() { return emissionFactorId; }
    public void setEmissionFactorId(String emissionFactorId) { this.emissionFactorId = emissionFactorId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public BigDecimal getCo2Factor() { return co2Factor; }
    public void setCo2Factor(BigDecimal co2Factor) { this.co2Factor = co2Factor; }

    public BigDecimal getCh4Factor() { return ch4Factor; }
    public void setCh4Factor(BigDecimal ch4Factor) { this.ch4Factor = ch4Factor; }

    public BigDecimal getN2oFactor() { return n2oFactor; }
    public void setN2oFactor(BigDecimal n2oFactor) { this.n2oFactor = n2oFactor; }

    public BigDecimal getCo2eFactor() { return co2eFactor; }
    public void setCo2eFactor(BigDecimal co2eFactor) { this.co2eFactor = co2eFactor; }

    public String getFactorUnit() { return factorUnit; }
    public void setFactorUnit(String factorUnit) { this.factorUnit = factorUnit; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getSourceYear() { return sourceYear; }
    public void setSourceYear(int sourceYear) { this.sourceYear = sourceYear; }

    public String getGeography() { return geography; }
    public void setGeography(String geography) { this.geography = geography; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getEffectiveStart() { return effectiveStart; }
    public void setEffectiveStart(LocalDate effectiveStart) { this.effectiveStart = effectiveStart; }

    public LocalDate getEffectiveEnd() { return effectiveEnd; }
    public void setEffectiveEnd(LocalDate effectiveEnd) { this.effectiveEnd = effectiveEnd; }
}
