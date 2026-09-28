package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code inventory_snapshots} row (V1: {@code NUMERIC(18,6)} tonnes columns,
 * {@code status IN ('ACTIVE','LOCKED','REVERTED')}, {@code snapshot_hash}
 * VARCHAR(64)). Field names follow the Node reference record
 * ({@code scope1Co2eT}, {@code scope2LocationCo2eT}, …) for contract parity.
 *
 * <p>The three value columns hold Scope 1 and the two Scope 2 perspectives
 * side by side — never summed together (ADR-002 dual reporting). The V1 schema
 * has no Scope 3 or biogenic source records, so {@code biogenicCo2eT} is
 * persisted as {@code 0} (absence of data, not a claim) and Scope 3 totals are
 * not representable in a snapshot — both documented in ADR-019.
 *
 * <p>{@code auditId} is schema-nullable and remains {@code null}: neither the
 * Node oracle nor the Phase 7 contract links snapshots to audits, and no
 * provenance claim is invented. {@code snapshotHash} is a reproducible
 * SHA-256 over the stored values (no timestamp — ADR-019).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventorySnapshot {

    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String auditId;
    private BigDecimal scope1Co2eT;
    private BigDecimal scope2LocationCo2eT;
    private BigDecimal scope2MarketCo2eT;
    private BigDecimal biogenicCo2eT;
    private String status;
    private String snapshotHash;
    private Instant createdAt;

    public InventorySnapshot() {
    }

    public InventorySnapshot(String id, String organizationId, String reportingPeriodId,
                             String auditId, BigDecimal scope1Co2eT,
                             BigDecimal scope2LocationCo2eT, BigDecimal scope2MarketCo2eT,
                             BigDecimal biogenicCo2eT, String status, String snapshotHash,
                             Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.reportingPeriodId = reportingPeriodId;
        this.auditId = auditId;
        this.scope1Co2eT = scope1Co2eT;
        this.scope2LocationCo2eT = scope2LocationCo2eT;
        this.scope2MarketCo2eT = scope2MarketCo2eT;
        this.biogenicCo2eT = biogenicCo2eT;
        this.status = status;
        this.snapshotHash = snapshotHash;
        this.createdAt = createdAt;
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

    public String getReportingPeriodId() {
        return reportingPeriodId;
    }

    public void setReportingPeriodId(String reportingPeriodId) {
        this.reportingPeriodId = reportingPeriodId;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public BigDecimal getScope1Co2eT() {
        return scope1Co2eT;
    }

    public void setScope1Co2eT(BigDecimal scope1Co2eT) {
        this.scope1Co2eT = scope1Co2eT;
    }

    public BigDecimal getScope2LocationCo2eT() {
        return scope2LocationCo2eT;
    }

    public void setScope2LocationCo2eT(BigDecimal scope2LocationCo2eT) {
        this.scope2LocationCo2eT = scope2LocationCo2eT;
    }

    public BigDecimal getScope2MarketCo2eT() {
        return scope2MarketCo2eT;
    }

    public void setScope2MarketCo2eT(BigDecimal scope2MarketCo2eT) {
        this.scope2MarketCo2eT = scope2MarketCo2eT;
    }

    public BigDecimal getBiogenicCo2eT() {
        return biogenicCo2eT;
    }

    public void setBiogenicCo2eT(BigDecimal biogenicCo2eT) {
        this.biogenicCo2eT = biogenicCo2eT;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public void setSnapshotHash(String snapshotHash) {
        this.snapshotHash = snapshotHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
