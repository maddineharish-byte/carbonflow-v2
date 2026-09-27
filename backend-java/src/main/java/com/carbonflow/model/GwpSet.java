package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code gwp_sets} (+ optionally {@code gwp_values}) in Node's wire shape.
 *
 * <p>The set is immutable reference data identified by its code
 * ({@code IPCC_AR6} …); calculations persist the set id and name they used
 * plus the per-gas values actually applied, so later changes to a GWP set
 * cannot restate historical results.
 *
 * <p>{@code values} is populated by the list endpoint and by calculation
 * resolution; it stays {@code null} (and is therefore omitted) on set
 * lookups that do not need it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GwpSet {

    private String id;
    private String code;
    private String name;
    private String assessmentReport;
    private int publicationYear;
    private boolean isDefault;
    private List<GwpValue> values;

    public GwpSet() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAssessmentReport() { return assessmentReport; }
    public void setAssessmentReport(String assessmentReport) { this.assessmentReport = assessmentReport; }

    public int getPublicationYear() { return publicationYear; }
    public void setPublicationYear(int publicationYear) { this.publicationYear = publicationYear; }

    // Node's field is `isDefault`; Jackson would otherwise strip the "is"
    // prefix and emit `default` (same treatment as Phase 5's checklist flags).
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }

    @JsonProperty("isDefault")
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public List<GwpValue> getValues() { return values; }
    public void setValues(List<GwpValue> values) { this.values = values; }
}
