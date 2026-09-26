package com.carbonflow.model;

import java.time.Instant;

public class EvidenceItem {
    private String id;
    private String organizationId;
    private String facilityId;
    private String fileName;
    private String fileType;
    private Long fileSizeBytes;
    private String sha256Checksum; // Immutable cryptographic proof
    private String storageUri;
    private String uploadedBy;
    private String verificationStatus; // PENDING_VERIFICATION, VERIFIED, REJECTED
    private Instant createdAt;

    public EvidenceItem() {}

    public EvidenceItem(String id, String organizationId, String facilityId, String fileName,
                        String fileType, Long fileSizeBytes, String sha256Checksum,
                        String storageUri, String uploadedBy, String verificationStatus, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.facilityId = facilityId;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileSizeBytes = fileSizeBytes;
        this.sha256Checksum = sha256Checksum;
        this.storageUri = storageUri;
        this.uploadedBy = uploadedBy;
        this.verificationStatus = verificationStatus;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getFacilityId() { return facilityId; }
    public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getSha256Checksum() { return sha256Checksum; }
    public void setSha256Checksum(String sha256Checksum) { this.sha256Checksum = sha256Checksum; }

    public String getStorageUri() { return storageUri; }
    public void setStorageUri(String storageUri) { this.storageUri = storageUri; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
