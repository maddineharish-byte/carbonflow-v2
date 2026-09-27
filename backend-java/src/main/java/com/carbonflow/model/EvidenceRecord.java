package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code evidence_records} row (V1) — private evidence vault metadata.
 *
 * <p>{@code storagePath} is {@link JsonIgnore @JsonIgnore}: the physical
 * vault path never leaves the server (Node's {@code getPublicEvidence}
 * strips it for the same reason). The file bytes travel only through the
 * download endpoint after {@code evidence.read} authorization.
 *
 * <p>{@code links} / {@code versions} are read enrichments from
 * {@code evidence_links} / {@code evidence_versions}. History is append-only:
 * a new version never overwrites the previous file or hash.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceRecord {

    private String id;
    private String organizationId;
    private String fileName;
    private long fileSizeBytes;
    private String mimeType;
    private String sha256Hash;
    @JsonIgnore
    private String storagePath;
    private String uploadedBy;
    private Instant createdAt;
    private List<EvidenceLink> links;
    private List<EvidenceVersion> versions;

    public EvidenceRecord() {
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
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

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<EvidenceLink> getLinks() {
        return links;
    }

    public void setLinks(List<EvidenceLink> links) {
        this.links = links;
    }

    public List<EvidenceVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<EvidenceVersion> versions) {
        this.versions = versions;
    }
}
