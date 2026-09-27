package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code evidence_versions} row (V1) — append-only version history for an
 * evidence record ({@code UNIQUE(evidence_record_id, version_number)}).
 * Every historical file/hash pair is preserved; versions are never
 * overwritten or silently dropped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceVersion {

    private String id;
    private String evidenceRecordId;
    private int versionNumber;
    private String sha256Hash;
    @JsonIgnore
    private String storagePath;
    private Instant createdAt;

    public EvidenceVersion() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEvidenceRecordId() {
        return evidenceRecordId;
    }

    public void setEvidenceRecordId(String evidenceRecordId) {
        this.evidenceRecordId = evidenceRecordId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public void setSha256Hash(String sha256Hash) {
        this.sha256Hash = sha256Hash;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
