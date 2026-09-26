package com.carbonflow.model;

import com.carbonflow.model.enums.OrganizationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A tenant: the {@code organizations} row joined with its
 * {@code organization_settings} (consolidation approach and base year, V1).
 * Mirrors the shape the Node reference backend exposes for
 * {@code GET/PUT /organizations/current} — {@code @JsonInclude(NON_NULL)}
 * reproduces Node's behaviour of omitting {@code undefined} fields (e.g. a
 * missing {@code taxId}) from the wire. {@code status} is the Java-only V8
 * lifecycle addition (ADR-014).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Organization {
    private String id;
    private String name;
    private String taxId;
    private String country;
    private String industry;
    private String consolidationApproach;
    private int baseYear;
    private OrganizationStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public Organization() {
    }

    public Organization(String id, String name, String taxId, String country, String industry,
                        String consolidationApproach, int baseYear, OrganizationStatus status,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.taxId = taxId;
        this.country = country;
        this.industry = industry;
        this.consolidationApproach = consolidationApproach;
        this.baseYear = baseYear;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getConsolidationApproach() { return consolidationApproach; }
    public void setConsolidationApproach(String consolidationApproach) { this.consolidationApproach = consolidationApproach; }

    public int getBaseYear() { return baseYear; }
    public void setBaseYear(int baseYear) { this.baseYear = baseYear; }

    public OrganizationStatus getStatus() { return status; }
    public void setStatus(OrganizationStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
