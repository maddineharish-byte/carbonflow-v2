package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.Map;

public class CalculationRequest {
    private String activityDataId;
    private String emissionFactorId;

    public String getActivityDataId() { return activityDataId; }
    public void setActivityDataId(String activityDataId) { this.activityDataId = activityDataId; }

    public String getEmissionFactorId() { return emissionFactorId; }
    public void setEmissionFactorId(String emissionFactorId) { this.emissionFactorId = emissionFactorId; }
}
