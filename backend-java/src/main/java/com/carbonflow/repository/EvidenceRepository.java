package com.carbonflow.repository;

import com.carbonflow.model.EvidenceLink;
import com.carbonflow.model.EvidenceRecord;
import com.carbonflow.model.EvidenceVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code evidence_records}, {@code evidence_versions} and
 * {@code evidence_links} (ADR-009).
 *
 * <p><b>Tenant-isolation audit:</b> evidence_records carries
 * {@code organization_id} directly — every record statement predicates on
 * it. evidence_versions and evidence_links have no organization column:
 * their statements either anchor on an already tenant-resolved record id
 * (versions) or join through the record (links), so neither table can leak
 * across tenants. Link inserts are only reachable after the service has
 * validated <i>both</i> the record and the target entity against the
 * caller's organization.
 *
 * <p>History is append-only: versions are inserted with
 * {@code max(version_number) + 1} inside the calling transaction
 * ({@code UNIQUE(evidence_record_id, version_number)} backstops races), and
 * deletion of historical files happens only under the governed delete rule
 * enforced in {@code EvidenceService}.
 */
@Repository
public class EvidenceRepository {

    static final String RECORD_COLUMNS =
            "id::text, organization_id::text, file_name, file_size_bytes, mime_type, "
                    + "sha256_hash, storage_path, uploaded_by::text, created_at";

    static final RowMapper<EvidenceRecord> RECORD_MAPPER = (rs, rowNum) -> {
        EvidenceRecord record = new EvidenceRecord();
        record.setId(rs.getString("id"));
        record.setOrganizationId(rs.getString("organization_id"));
        record.setFileName(rs.getString("file_name"));
        record.setFileSizeBytes(rs.getLong("file_size_bytes"));
        record.setMimeType(rs.getString("mime_type"));
        record.setSha256Hash(rs.getString("sha256_hash"));
        record.setStoragePath(rs.getString("storage_path"));
        record.setUploadedBy(rs.getString("uploaded_by"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        record.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return record;
    };

    static final RowMapper<EvidenceLink> LINK_MAPPER = (rs, rowNum) -> {
        EvidenceLink link = new EvidenceLink();
        link.setId(rs.getString("id"));
        link.setEvidenceRecordId(rs.getString("evidence_record_id"));
        link.setEntityType(rs.getString("entity_type"));
        link.setEntityId(rs.getString("entity_id"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        link.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return link;
    };

    static final RowMapper<EvidenceVersion> VERSION_MAPPER = (rs, rowNum) -> {
        EvidenceVersion version = new EvidenceVersion();
        version.setId(rs.getString("id"));
        version.setEvidenceRecordId(rs.getString("evidence_record_id"));
        version.setVersionNumber(rs.getInt("version_number"));
        version.setSha256Hash(rs.getString("sha256_hash"));
        version.setStoragePath(rs.getString("storage_path"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        version.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return version;
    };

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // evidence_records
    // ------------------------------------------------------------------

    /** Newest first — matches the Node production list ordering. */
    public List<EvidenceRecord> list(String organizationId) {
        return jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM evidence_records "
                        + "WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id",
                RECORD_MAPPER, organizationId);
    }

    public Optional<EvidenceRecord> findById(String organizationId, String evidenceId) {
        List<EvidenceRecord> rows = jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM evidence_records "
                        + "WHERE organization_id = ? AND id = ?",
                RECORD_MAPPER, organizationId, evidenceId);
        return rows.stream().findFirst();
    }

    public EvidenceRecord insert(String organizationId, String uploadedBy, String fileName,
                                 long fileSizeBytes, String mimeType, String sha256Hash,
                                 String storagePath) {
        return jdbc.queryForObject(
                "INSERT INTO evidence_records "
                        + "(organization_id, file_name, file_size_bytes, mime_type, "
                        + "sha256_hash, storage_path, uploaded_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + RECORD_COLUMNS,
                RECORD_MAPPER, organizationId, fileName, fileSizeBytes, mimeType,
                sha256Hash, storagePath, uploadedBy);
    }

    /** Points the record at its newest version (history rows stay untouched). */
    public int updateCurrent(String organizationId, String evidenceId, long fileSizeBytes,
                             String sha256Hash, String storagePath) {
        return jdbc.update(
                "UPDATE evidence_records SET file_size_bytes = ?, sha256_hash = ?, "
                        + "storage_path = ? WHERE organization_id = ? AND id = ?",
                fileSizeBytes, sha256Hash, storagePath, organizationId, evidenceId);
    }

    /**
     * Governed delete: row removed only after the service proved the record
     * is not audit-linked; {@code evidence_versions} and {@code evidence_links}
     * cascade from V1, and the service removes the physical files.
     */
    public int delete(String organizationId, String evidenceId) {
        return jdbc.update(
                "DELETE FROM evidence_records WHERE organization_id = ? AND id = ?",
                organizationId, evidenceId);
    }

    // ------------------------------------------------------------------
    // evidence_links
    // ------------------------------------------------------------------

    public List<EvidenceLink> listLinks(String organizationId, String evidenceId) {
        return jdbc.query(
                "SELECT el.id::text, el.evidence_record_id::text, el.entity_type, "
                        + "el.entity_id::text, el.created_at "
                        + "FROM evidence_links el "
                        + "JOIN evidence_records er ON er.id = el.evidence_record_id "
                        + "WHERE er.organization_id = ? AND el.evidence_record_id = ? "
                        + "ORDER BY el.created_at, el.id",
                LINK_MAPPER, organizationId, evidenceId);
    }

    public EvidenceLink insertLink(String evidenceId, String entityType, String entityId) {
        return jdbc.queryForObject(
                "INSERT INTO evidence_links (evidence_record_id, entity_type, entity_id) "
                        + "VALUES (?, ?, ?) "
                        + "RETURNING id::text, evidence_record_id::text, entity_type, "
                        + "entity_id::text, created_at",
                LINK_MAPPER, evidenceId, entityType, entityId);
    }

    public boolean linkExists(String organizationId, String evidenceId, String entityType,
                              String entityId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_links el "
                        + "JOIN evidence_records er ON er.id = el.evidence_record_id "
                        + "WHERE er.organization_id = ? AND el.evidence_record_id = ? "
                        + "AND el.entity_type = ? AND el.entity_id = ?",
                Integer.class, organizationId, evidenceId, entityType, entityId);
        return count != null && count > 0;
    }

    /**
     * Governed-delete rule input: does any link of this record point at an
     * audit? Audit-linked evidence is part of the audit record and cannot be
     * permanently deleted (Phase 5 module 9 decision, ADR-016).
     */
    public boolean isAuditLinked(String organizationId, String evidenceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_links el "
                        + "JOIN evidence_records er ON er.id = el.evidence_record_id "
                        + "WHERE er.organization_id = ? AND el.evidence_record_id = ? "
                        + "AND el.entity_type = 'AUDIT'",
                Integer.class, organizationId, evidenceId);
        return count != null && count > 0;
    }

    /** True when the record is linked to a LOCKED audit (mutation freeze). */
    public boolean linkedToLockedAudit(String organizationId, String evidenceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_links el "
                        + "JOIN evidence_records er ON er.id = el.evidence_record_id "
                        + "JOIN carbon_audits a ON a.id::text = el.entity_id::text "
                        + "WHERE er.organization_id = ? AND el.evidence_record_id = ? "
                        + "AND a.organization_id = er.organization_id "
                        + "AND el.entity_type = 'AUDIT' AND a.status = 'LOCKED'",
                Integer.class, organizationId, evidenceId);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // evidence_versions
    // ------------------------------------------------------------------

    public List<EvidenceVersion> listVersions(String organizationId, String evidenceId) {
        return jdbc.query(
                "SELECT ev.id::text, ev.evidence_record_id::text, ev.version_number, "
                        + "ev.sha256_hash, ev.storage_path, ev.created_at "
                        + "FROM evidence_versions ev "
                        + "JOIN evidence_records er ON er.id = ev.evidence_record_id "
                        + "WHERE er.organization_id = ? AND ev.evidence_record_id = ? "
                        + "ORDER BY ev.version_number",
                VERSION_MAPPER, organizationId, evidenceId);
    }

    public EvidenceVersion insertVersion(String evidenceId, int versionNumber,
                                         String sha256Hash, String storagePath) {
        return jdbc.queryForObject(
                "INSERT INTO evidence_versions "
                        + "(evidence_record_id, version_number, sha256_hash, storage_path) "
                        + "VALUES (?, ?, ?, ?) "
                        + "RETURNING id::text, evidence_record_id::text, version_number, "
                        + "sha256_hash, storage_path, created_at",
                VERSION_MAPPER, evidenceId, versionNumber, sha256Hash, storagePath);
    }

    /** {@code MAX(version_number) + 1}; 1 for a record with no history rows. */
    public int nextVersionNumber(String evidenceId) {
        Integer max = jdbc.queryForObject(
                "SELECT max(version_number) FROM evidence_versions "
                        + "WHERE evidence_record_id = ?",
                Integer.class, evidenceId);
        return (max == null ? 0 : max) + 1;
    }
}
