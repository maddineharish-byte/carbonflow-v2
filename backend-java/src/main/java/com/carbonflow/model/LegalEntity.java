package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code legal_entities} row (V1 columns, V4 {@code ownership_percentage}
 * range check 0..100). Mirrors the Node reference backend's
 * {@code LegalEntityRecord} (server/scope-repository.ts) field for field.
 *
 * <p>The schema carries no uniqueness on name or registration number, so
 * duplicate names/registrations within a tenant are permitted (no constraint
 * is invented — documented in ADR-015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LegalEntity {

    private String id;
    private String organizationId;
    private String name;
    private String jurisdiction;
    private String registrationNumber;
    private Double ownershipPercentage;
    private Instant createdAt;
    private Instant updatedAt;

    public LegalEntity() {
    }

    public LegalEntity(String id, String organizationId, String name, String jurisdiction,
                       String registrationNumber, Double ownershipPercentage,
                       Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.jurisdiction = jurisdiction;
        this.registrationNumber = registrationNumber;
        this.ownershipPercentage = ownershipPercentage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public Double getOwnershipPercentage() {
        return ownershipPercentage;
    }

    public void setOwnershipPercentage(Double ownershipPercentage) {
        this.ownershipPercentage = ownershipPercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
