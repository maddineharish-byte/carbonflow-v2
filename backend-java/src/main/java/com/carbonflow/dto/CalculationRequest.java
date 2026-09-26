package com.carbonflow.dto;

import jakarta.validation.constraints.NotBlank;

public class CalculationRequest {
    @NotBlank(message = "activityDataId is required")
    private String activityDataId;
    @NotBlank(message = "emissionFactorId is required")
    private String emissionFactorId;

    public String getActivityDataId() { return activityDataId; }
    public void setActivityDataId(String activityDataId) { this.activityDataId = activityDataId; }

    public String getEmissionFactorId() { return emissionFactorId; }
    public void setEmissionFactorId(String emissionFactorId) { this.emissionFactorId = emissionFactorId; }
}
