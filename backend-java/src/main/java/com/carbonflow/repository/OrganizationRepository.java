package com.carbonflow.repository;

import com.carbonflow.model.Organization;
import com.carbonflow.model.enums.OrganizationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code organizations} + {@code organization_settings}
 * (ADR-009 — no ORM). Every query is tenant-agnostic on purpose: which
 * organization a caller may touch is decided by the authenticated tenant
 * context upstream, never by a client-supplied id.
 */
@Repository
public class OrganizationRepository {

    static final String SELECT_COLUMNS =
            "o.id::text AS id, o.name AS name, o.tax_id AS tax_id, o.country AS country, "
                    + "o.industry AS industry, o.status AS status, o.created_at AS created_at, "
                    + "o.updated_at AS updated_at, "
                    + "COALESCE(os.consolidation_approach, 'OPERATIONAL_CONTROL') AS consolidation_approach, "
                    + "COALESCE(os.base_year, 2023) AS base_year ";

    static final String FROM_JOIN =
            "FROM organizations o LEFT JOIN organization_settings os ON os.organization_id = o.id ";

    static final RowMapper<Organization> MAPPER = (rs, rowNum) -> new Organization(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("tax_id"),
            rs.getString("country"),
            rs.getString("industry"),
            rs.getString("consolidation_approach"),
            rs.getInt("base_year"),
            OrganizationStatus.fromDb(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public OrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Organization> findById(String organizationId) {
        List<Organization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + FROM_JOIN + "WHERE o.id = ?",
                MAPPER, organizationId);
        return rows.stream().findFirst();
    }

    /** Platform administration listing; {@code status} may be null for all. */
    public List<Organization> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(
                    "SELECT " + SELECT_COLUMNS + FROM_JOIN + "ORDER BY o.created_at DESC, o.name",
                    MAPPER);
        }
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + FROM_JOIN + "WHERE o.status = ? ORDER BY o.created_at DESC, o.name",
                MAPPER, status);
    }

    /**
     * Creates an organization with an explicit lifecycle status
     * ({@code PENDING_ACTIVATION} for registrations, {@code ACTIVE} for seeds).
     * Returns 0 when the fixed id already exists (idempotent seeding).
     */
    public int insert(String organizationId, String name, String country, String industry, String taxId,
                      OrganizationStatus status) {
        return jdbc.update(
                "INSERT INTO organizations (id, name, country, industry, tax_id, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                organizationId, name, country, industry, taxId, status.name());
    }

    /** Updates the tenant profile fields accepted by {@code PUT /organizations/current}. */
    public int updateProfile(Organization organization) {
        int updated = jdbc.update(
                "UPDATE organizations SET name = ?, country = ?, industry = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                organization.getName(), organization.getCountry(), organization.getIndustry(),
                organization.getId());
        if (updated > 0) {
            jdbc.update(
                    "INSERT INTO organization_settings (organization_id, consolidation_approach, base_year) "
                            + "VALUES (?, ?, ?) "
                            + "ON CONFLICT (organization_id) DO UPDATE "
                            + "SET consolidation_approach = EXCLUDED.consolidation_approach, "
                            + "base_year = EXCLUDED.base_year, updated_at = CURRENT_TIMESTAMP",
                    organization.getId(), organization.getConsolidationApproach(), organization.getBaseYear());
        }
        return updated;
    }

    /** Lifecycle transition written by the platform administration endpoints (V8). */
    public int updateStatus(String organizationId, OrganizationStatus status, String changedByUserId, String note) {
        return jdbc.update(
                "UPDATE organizations SET status = ?, status_changed_at = CURRENT_TIMESTAMP, "
                        + "status_changed_by = ?, status_note = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                status.name(), changedByUserId, note, organizationId);
    }
}
