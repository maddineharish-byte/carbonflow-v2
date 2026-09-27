package com.carbonflow.service;

import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.model.EvidenceLink;
import com.carbonflow.model.EvidenceRecord;
import com.carbonflow.model.EvidenceVersion;
import com.carbonflow.repository.EvidenceRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Evidence vault service — org-scoped, access-controlled evidence records
 * with version history and tenant-validated linking.
 *
 * <p><b>Validation preserved from Node</b> (Phase 1 requirements, module 8):
 * file present → metadata length checks → entity relationship validation →
 * storage validation (25 MB → MIME allow-list → magic bytes → SHA-256 →
 * sanitized path) → DB writes with <b>cleanup of the stored file on any
 * failed write</b>. None of it was simplified for the Java port.
 *
 * <p><b>Tenancy:</b> the record resolves through
 * {@code ScopeService.requireEvidence} ({@code organization_id = ?}), and
 * every link target (ACTIVITY_DATA / AUDIT / FACILITY) is resolved against
 * the same organization before the insert — a cross-tenant link can never be
 * written. Entity failures answer Node's uniform
 * {@code 400 INVALID_EVIDENCE_RELATIONSHIP} regardless of whether the id is
 * malformed, missing or foreign (no existence oracle).
 *
 * <p><b>Versioning</b> is append-only: upload writes version 1 alongside the
 * record, each new version adds a {@code max+1} row and repoints the record
 * (the head); previous files and hashes are never overwritten. <b>Deletion</b>
 * follows the governed rule: evidence linked to any audit is part of the
 * audit record and cannot be deleted ({@code 409 EVIDENCE_IN_USE});
 * audit-linked evidence also freezes at lock ({@code 409 AUDIT_LOCKED}).
 */
@Service
public class EvidenceService {

    private static final Set<String> LINK_TYPES =
            Set.of("ACTIVITY_DATA", "AUDIT", "FACILITY");

    private final ScopeService scope;
    private final EvidenceRepository evidence;
    private final EvidenceStorageService storage;
    private final AuditService auditService;

    public EvidenceService(ScopeService scope, EvidenceRepository evidence,
                           EvidenceStorageService storage, AuditService auditService) {
        this.scope = scope;
        this.evidence = evidence;
        this.storage = storage;
        this.auditService = auditService;
    }

    /** Download payload: resolved record + its bytes. */
    public static final class Download {
        private final EvidenceRecord record;
        private final byte[] bytes;

        public Download(EvidenceRecord record, byte[] bytes) {
            this.record = record;
            this.bytes = bytes;
        }

        public EvidenceRecord getRecord() {
            return record;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Node list shape: every record enriched with its links. */
    public List<EvidenceRecord> list(String organizationId) {
        return evidence.list(organizationId).stream()
                .map(record -> withLinks(organizationId, record))
                .toList();
    }

    /** Detail adds version history (additive to the Node list shape). */
    public EvidenceRecord detail(String organizationId, String evidenceId) {
        EvidenceRecord record = scope.requireEvidence(organizationId, evidenceId);
        record = withLinks(organizationId, record);
        record.setVersions(evidence.listVersions(organizationId, evidenceId));
        return record;
    }

    private EvidenceRecord withLinks(String organizationId, EvidenceRecord record) {
        record.setLinks(evidence.listLinks(organizationId, record.getId()));
        return record;
    }

    public Download download(String organizationId, String evidenceId) throws IOException {
        EvidenceRecord record = scope.requireEvidence(organizationId, evidenceId);
        byte[] bytes = storage.readFile(record.getStoragePath());
        return new Download(record, bytes);
    }

    // ------------------------------------------------------------------
    // Upload / versioning / linking / governed delete
    // ------------------------------------------------------------------

    @Transactional
    public EvidenceRecord upload(String organizationId, String userId, MultipartFile file,
                                 String entityType, String entityId) throws IOException {
        assertFilePresent(file);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        assertMetadataValid(fileName);

        // Entity validation runs before any file is written (Node order).
        boolean wantsLink = entityType != null || entityId != null;
        String normalizedType = wantsLink
                ? resolveLinkTarget(organizationId, entityType, entityId) : null;

        EvidenceRecord record;
        EvidenceStorageService.StoredFile stored = null;
        try {
            stored = storage.saveFile(organizationId, fileName, file.getContentType(),
                    file.getBytes());
            record = evidence.insert(organizationId, userId, stored.getFileName(),
                    stored.getFileSizeBytes(), stored.getMimeType(),
                    stored.getSha256Hash(), stored.getStoragePath());
            // Version 1 history row — provenance from the very first upload.
            evidence.insertVersion(record.getId(), 1, stored.getSha256Hash(),
                    stored.getStoragePath());
            if (wantsLink) {
                evidence.insertLink(record.getId(), normalizedType, entityId.trim());
            }
            return record;
        } catch (IOException | RuntimeException e) {
            // Node: cleanup the stored file whenever the DB step fails.
            if (stored != null) {
                storage.deleteFile(stored.getStoragePath());
            }
            throw e;
        }
    }

    @Transactional
    public EvidenceVersion createVersion(String organizationId, String userId,
                                         String evidenceId, MultipartFile file)
            throws IOException {
        EvidenceRecord record = scope.requireEvidence(organizationId, evidenceId);
        assertNotFrozen(organizationId, evidenceId);
        assertFilePresent(file);
        assertMetadataValid(file.getOriginalFilename() == null ? ""
                : file.getOriginalFilename());

        EvidenceStorageService.StoredFile stored = null;
        try {
            stored = storage.saveFile(organizationId, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
            int versionNumber = evidence.nextVersionNumber(record.getId());
            EvidenceVersion version = evidence.insertVersion(record.getId(), versionNumber,
                    stored.getSha256Hash(), stored.getStoragePath());
            evidence.updateCurrent(organizationId, evidenceId, stored.getFileSizeBytes(),
                    stored.getSha256Hash(), stored.getStoragePath());
            return version;
        } catch (IOException | RuntimeException e) {
            if (stored != null) {
                storage.deleteFile(stored.getStoragePath());
            }
            throw e;
        }
    }

    @Transactional
    public EvidenceLink link(String organizationId, String evidenceId,
                             GovernanceRequests.EvidenceLinkRequest request) {
        scope.requireEvidence(organizationId, evidenceId);
        assertNotFrozen(organizationId, evidenceId);
        if (request == null) {
            throw invalidRelationship();
        }
        String normalizedType =
                resolveLinkTarget(organizationId, request.getEntityType(),
                        request.getEntityId());
        String entityId = request.getEntityId().trim();

        if (evidence.linkExists(organizationId, evidenceId, normalizedType, entityId)) {
            throw new AuthException("DUPLICATE_EVIDENCE_LINK",
                    "Evidence is already linked to this entity.",
                    HttpStatus.CONFLICT);
        }
        return evidence.insertLink(evidenceId, normalizedType, entityId);
    }

    /**
     * Governed delete: audit-linked evidence is historical audit material
     * and is never permanently deleted; everything else loses its rows
     * (versions + links cascade) and its vault files.
     */
    @Transactional
    public void delete(String organizationId, String evidenceId) {
        EvidenceRecord record = scope.requireEvidence(organizationId, evidenceId);
        if (evidence.isAuditLinked(organizationId, evidenceId)) {
            throw new AuthException("EVIDENCE_IN_USE",
                    "Evidence is linked to an audit record and cannot be deleted.",
                    HttpStatus.CONFLICT);
        }
        Set<String> paths = new LinkedHashSet<>();
        paths.add(record.getStoragePath());
        for (EvidenceVersion version : evidence.listVersions(organizationId, evidenceId)) {
            paths.add(version.getStoragePath());
        }

        evidence.delete(organizationId, evidenceId);
        // Files only after their rows are gone — an orphaned file is inert,
        // a dangling row would expose a broken record.
        paths.forEach(storage::deleteFile);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Resolves a link target strictly inside the caller's organization and
     * returns the normalized entity type. Every failure mode — unknown type,
     * malformed id, nonexistent row, foreign-tenant row — collapses into the
     * single Node response {@code 400 INVALID_EVIDENCE_RELATIONSHIP}
     * "Evidence entity is invalid for this organization."; conflict-level
     * errors (locked audit) keep their own code.
     */
    private String resolveLinkTarget(String organizationId, String entityType,
                                     String entityId) {
        if (entityType == null || entityType.isBlank()
                || entityId == null || entityId.isBlank()) {
            throw invalidRelationship();
        }
        String type = entityType.trim().toUpperCase();
        if (!LINK_TYPES.contains(type)) {
            throw invalidRelationship();
        }
        try {
            switch (type) {
                case "ACTIVITY_DATA" -> scope.requireActivityData(organizationId,
                        entityId.trim());
                case "AUDIT" -> {
                    CarbonAudit audit = scope.requireAudit(organizationId, entityId.trim());
                    auditService.requireUnlocked(audit);
                }
                case "FACILITY" -> scope.requireFacility(organizationId, entityId.trim());
                default -> throw invalidRelationship();
            }
        } catch (AuthException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                throw invalidRelationship();
            }
            throw e;
        }
        return type;
    }

    private static AuthException invalidRelationship() {
        return new AuthException("INVALID_EVIDENCE_RELATIONSHIP",
                "Evidence entity is invalid for this organization.",
                HttpStatus.BAD_REQUEST);
    }

    /** A record already linked to a LOCKED audit is frozen (module 12). */
    private void assertNotFrozen(String organizationId, String evidenceId) {
        if (evidence.linkedToLockedAudit(organizationId, evidenceId)) {
            throw new AuthException("AUDIT_LOCKED",
                    "Audit is locked and cannot be modified.",
                    HttpStatus.CONFLICT);
        }
    }

    private static void assertFilePresent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AuthException("FILE_MISSING",
                    "No file was uploaded in the request.", HttpStatus.BAD_REQUEST);
        }
    }

    private static void assertMetadataValid(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255) {
            // Node production repository rule (createEvidence metadata check).
            throw new AuthException("VALIDATION_ERROR",
                    "Evidence metadata is invalid.", HttpStatus.BAD_REQUEST);
        }
    }
}
