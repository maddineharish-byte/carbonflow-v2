package com.carbonflow.model;

import com.carbonflow.model.enums.EmissionCategory;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;

import java.math.BigDecimal;

public class EmissionFactor {
    private String id;
    private String sourceName; // e.g. "EPA eGRID 2024", "DEFRA 2024", "IPCC AR6"
    private String region;
    private GHGScope scope;
    private EmissionCategory category;
    private Scope2Method scope2Type;
    private String activityType;
    private String activityUnit;
    private BigDecimal factorValue; // kgCO2e per activity unit
    private String gasBreakdown; // JSON string or summary e.g. "CO2: 98%, CH4: 1.5%, N2O: 0.5%"
    private int year;
    private String referenceUrl;

    public EmissionFactor() {}

    public EmissionFactor(String id, String sourceName, String region, GHGScope scope, EmissionCategory category,
                          Scope2Method scope2Type, String activityType, String activityUnit,
                          BigDecimal factorValue, String gasBreakdown, int year, String referenceUrl) {
        this.id = id;
        this.sourceName = sourceName;
        this.region = region;
        this.scope = scope;
        this.category = category;
        this.scope2Type = scope2Type;
        this.activityType = activityType;
        this.activityUnit = activityUnit;
        this.factorValue = factorValue;
        this.gasBreakdown = gasBreakdown;
        this.year = year;
        this.referenceUrl = referenceUrl;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public EmissionCategory getCategory() { return category; }
    public void setCategory(EmissionCategory category) { this.category = category; }

    public Scope2Method getScope2Type() { return scope2Type; }
    public void setScope2Type(Scope2Method scope2Type) { this.scope2Type = scope2Type; }

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public String getActivityUnit() { return activityUnit; }
    public void setActivityUnit(String activityUnit) { this.activityUnit = activityUnit; }

    public BigDecimal getFactorValue() { return factorValue; }
    public void setFactorValue(BigDecimal factorValue) { this.factorValue = factorValue; }

    public String getGasBreakdown() { return gasBreakdown; }
    public void setGasBreakdown(String gasBreakdown) { this.gasBreakdown = gasBreakdown; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public String getReferenceUrl() { return referenceUrl; }
    public void setReferenceUrl(String referenceUrl) { this.referenceUrl = referenceUrl; }
}
