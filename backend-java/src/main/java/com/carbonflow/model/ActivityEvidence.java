package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The five evidence fields the Node reference embeds on an activity record
 * ({@code mapEvidence} in {@code server/activity-repository.ts}): the first
 * evidence record linked to the activity, never the full vault record — no
 * storage path, uploader or timestamps leak into the accounting payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityEvidence {

    private String id;
    private String fileName;
    private long fileSizeBytes;
    private String mimeType;
    private String sha256Hash;

    public ActivityEvidence() {
    }

    public ActivityEvidence(String id, String fileName, long fileSizeBytes,
                            String mimeType, String sha256Hash) {
        this.id = id;
        this.fileName = fileName;
        this.fileSizeBytes = fileSizeBytes;
        this.mimeType = mimeType;
        this.sha256Hash = sha256Hash;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
}
