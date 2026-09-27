package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code calculation_methodologies} row — the reference-only methodology
 * catalogue (GHG Protocol Corporate Standard, ISO 14064-1, …).
 *
 * <p><b>Scope of the model (established, ADR recorded in
 * {@code docs/DECISIONS.md}):</b> the established calculation engine takes no
 * methodology input, {@code calculations} has no methodology/formula column,
 * and no column was invented to hold one. Phase 6 exposes the catalogue
 * read-only so methodology identity and version are queryable provenance
 * reference data; calculations continue to record the factor/GWP/unit snapshot
 * they actually used.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CalculationMethodology {

    private String id;
    private String code;
    private String name;
    private String version;
    private String description;

    public CalculationMethodology() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
