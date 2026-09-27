package com.carbonflow.controller;

import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.EvidenceLink;
import com.carbonflow.model.EvidenceRecord;
import com.carbonflow.model.EvidenceVersion;
import com.carbonflow.config.TenantContext;
import com.carbonflow.service.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Evidence vault endpoints (API.md §2.7): org-scoped list/detail, validated
 * multipart upload, streaming download, tenant-validated linking,
 * append-only versioning and governed delete.
 *
 * <p>The storage path never appears in any response (the model ignores it);
 * bytes leave only through {@code GET /:id/download} after
 * {@code evidence.read} authorization. {@code storagePath} exposure was a
 * Node risk Java avoids by construction.
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    private static String orgId() {
        return TenantContext.get().getOrganizationId();
    }

    private static String userId() {
        return TenantContext.get().getUserId();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_evidence.read')")
    public ResponseEntity<ApiResponse<List<EvidenceRecord>>> listEvidence() {
        return ResponseEntity.ok(ApiResponse.ok(evidenceService.list(orgId())));
    }

    @GetMapping("/{evidenceId}")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.read')")
    public ResponseEntity<ApiResponse<EvidenceRecord>> getEvidence(
            @PathVariable String evidenceId) {
        return ResponseEntity.ok(ApiResponse.ok(evidenceService.detail(orgId(),
                evidenceId)));
    }

    /**
     * Multipart upload: optional form fields {@code entityType}/{@code
     * entityId} pre-validate the link target before any file is written;
     * validation order and error text match the Node reference (FILE_MISSING
     * → entity relationship → 25 MB → MIME → magic bytes → SHA-256).
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.upload')")
    public ResponseEntity<ApiResponse<EvidenceRecord>> uploadEvidence(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "entityId", required = false) String entityId)
            throws IOException {
        EvidenceRecord record =
                evidenceService.upload(orgId(), userId(), file, entityType, entityId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(record,
                "Evidence stored with SHA-256 verification."));
    }

    @GetMapping("/{evidenceId}/download")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.read')")
    public ResponseEntity<byte[]> downloadEvidence(@PathVariable String evidenceId)
            throws IOException {
        EvidenceService.Download download = evidenceService.download(orgId(), evidenceId);
        EvidenceRecord record = download.getRecord();

        // Node header sanitization: CR/LF/quote can never split headers.
        String safeName = record.getFileName().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"")
                .body(download.getBytes());
    }

    @PostMapping("/{evidenceId}/link")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.upload')")
    public ResponseEntity<ApiResponse<EvidenceLink>> linkEvidence(
            @PathVariable String evidenceId,
            @RequestBody GovernanceRequests.EvidenceLinkRequest request) {
        EvidenceLink link = evidenceService.link(orgId(), evidenceId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(link, "Evidence linked."));
    }

    @PostMapping("/{evidenceId}/versions")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.version')")
    public ResponseEntity<ApiResponse<EvidenceVersion>> createVersion(
            @PathVariable String evidenceId,
            @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        EvidenceVersion version =
                evidenceService.createVersion(orgId(), userId(), evidenceId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(version,
                "Evidence version " + version.getVersionNumber() + " created."));
    }

    @DeleteMapping("/{evidenceId}")
    @PreAuthorize("hasAuthority('PERMISSION_evidence.delete')")
    public ResponseEntity<ApiResponse<Void>> deleteEvidence(
            @PathVariable String evidenceId) {
        evidenceService.delete(orgId(), evidenceId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Evidence deleted."));
    }
}
