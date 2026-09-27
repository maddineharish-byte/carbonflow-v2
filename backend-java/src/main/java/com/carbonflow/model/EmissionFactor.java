package com.carbonflow.model;

import com.carbonflow.model.enums.GHGScope;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code emission_factors} row plus its {@code emission_factor_versions} —
 * the wire shape produced by Node's {@code listEmissionFactorsWithVersions}:
 * factors ordered by {@code activity_type, fuel_or_activity}, each carrying
 * its versions ordered by {@code version_number}.
 *
 * <p>Reference data is global (the V1 schema has no {@code organization_id}
 * on either table): every tenant of the platform reads the same factor
 * library, so no tenant predicate is possible or required here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmissionFactor {

    private String id;
    private GHGScope scope;
    private String category;
    private String activityType;
    private String fuelOrActivity;
    private String inputUnit;
    private List<EmissionFactorVersion> versions = new ArrayList<>();

    public EmissionFactor() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public String getFuelOrActivity() { return fuelOrActivity; }
    public void setFuelOrActivity(String fuelOrActivity) { this.fuelOrActivity = fuelOrActivity; }

    public String getInputUnit() { return inputUnit; }
    public void setInputUnit(String inputUnit) { this.inputUnit = inputUnit; }

    public List<EmissionFactorVersion> getVersions() { return versions; }
    public void setVersions(List<EmissionFactorVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : versions;
    }
}
