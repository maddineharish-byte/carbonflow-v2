package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code audit_lock_events} row (V1) — created exactly once by the governed
 * AUDIT_READY → LOCKED transition, preserving who locked the audit and when.
 *
 * <p>{@code inventoryHash} holds a SHA-256 <b>governance-state hash</b>
 * computed over the audit row, its checklist and its findings at lock time
 * (V7 column is NOT NULL; no inventory snapshot exists until Phase 7).
 * This is a data-integrity checksum for the governed lock — CarbonFlow
 * does <b>not</b> claim cryptographic immutability of the audit record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLockEvent {

    private String id;
    private String auditId;
    private String lockedBy;
    private String inventoryHash;
    private Instant lockedAt;

    public AuditLockEvent() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public String getInventoryHash() {
        return inventoryHash;
    }

    public void setInventoryHash(String inventoryHash) {
        this.inventoryHash = inventoryHash;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }
}
