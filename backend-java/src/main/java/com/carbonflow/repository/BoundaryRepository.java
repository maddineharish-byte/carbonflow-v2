package com.carbonflow.repository;

import com.carbonflow.model.OrganizationalBoundary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit SQL for {@code organizational_boundaries} + the
 * {@code boundary_facilities} membership (V1).
 *
 * <p><b>Tenant-isolation audit:</b> boundary rows are always read/written
 * with {@code organization_id = ?}. {@code boundary_facilities} has no
 * organization column of its own, so membership statements join or use the
 * parent boundary's tenant predicate:
 *
 * <ul>
 *   <li>{@link #attach} inserts only when the boundary <b>and</b> the facility
 *       belong to the same tenant ({@code JOIN facilities f ON f.organization_id
 *       = b.organization_id}), and only when that tenant is the caller's — a
 *       cross-tenant pairing matches zero rows and can never persist;</li>
 *   <li>{@link #detach}/{@link #facilityIds} likewise route through
 *       {@code organization_id = ?} on the parent boundary.</li>
 * </ul>
 *
 * <p>The V1 primary key {@code (boundary_id, facility_id)} makes duplicate
 * attachments impossible at the database level (surfaced as
 * {@code DuplicateKeyException} → 409 in the service).
 */
@Repository
public class BoundaryRepository {

    // Unprefixed on purpose: the same column list serves SELECT (aliased b)
    // and INSERT ... RETURNING, where no table alias exists.
    static final String COLUMNS =
            "id::text, organization_id::text, reporting_period_id::text, "
                    + "consolidation_approach, notes, created_at";

    static final RowMapper<OrganizationalBoundary> MAPPER = (rs, rowNum) ->
            new OrganizationalBoundary(
                    rs.getString("id"),
                    rs.getString("organization_id"),
                    rs.getString("reporting_period_id"),
                    rs.getString("consolidation_approach"),
                    rs.getString("notes"),
                    List.of(),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public BoundaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Boundaries of one tenant (deterministic order), each with its persisted facility ids. */
    public List<OrganizationalBoundary> list(String organizationId) {
        List<OrganizationalBoundary> boundaries = jdbc.query(
                "SELECT " + COLUMNS + " FROM organizational_boundaries b "
                        + "WHERE b.organization_id = ? ORDER BY b.created_at, b.id",
                MAPPER, organizationId);
        if (boundaries.isEmpty()) {
            return boundaries;
        }
        Map<String, List<String>> links = facilityLinks(organizationId);
        for (OrganizationalBoundary boundary : boundaries) {
            boundary.setFacilityIds(links.getOrDefault(boundary.getId(), List.of()));
        }
        return boundaries;
    }

    public Optional<OrganizationalBoundary> findById(String organizationId, String boundaryId) {
        List<OrganizationalBoundary> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM organizational_boundaries b "
                        + "WHERE b.organization_id = ? AND b.id = ?",
                MAPPER, organizationId, boundaryId);
        return rows.stream().findFirst().map(boundary -> {
            boundary.setFacilityIds(
                    facilityIds(organizationId, boundary.getId()));
            return boundary;
        });
    }

    /** All facility links of one tenant's boundaries, grouped by boundary id. */
    private Map<String, List<String>> facilityLinks(String organizationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT bf.boundary_id::text AS boundary_id, bf.facility_id::text AS facility_id "
                        + "FROM boundary_facilities bf "
                        + "JOIN organizational_boundaries b ON b.id = bf.boundary_id "
                        + "WHERE b.organization_id = ? ORDER BY bf.facility_id",
                organizationId);
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(String.valueOf(row.get("boundary_id")),
                    key -> new ArrayList<>()).add(String.valueOf(row.get("facility_id")));
        }
        return grouped;
    }

    public List<String> facilityIds(String organizationId, String boundaryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT bf.facility_id::text AS facility_id "
                        + "FROM boundary_facilities bf "
                        + "JOIN organizational_boundaries b ON b.id = bf.boundary_id "
                        + "WHERE b.organization_id = ? AND bf.boundary_id = ? "
                        + "ORDER BY bf.facility_id",
                organizationId, boundaryId);
        return rows.stream().map(row -> String.valueOf(row.get("facility_id"))).toList();
    }

    public OrganizationalBoundary insert(String organizationId, String reportingPeriodId,
                                         String consolidationApproach, String notes) {
        return jdbc.queryForObject(
                "INSERT INTO organizational_boundaries (organization_id, reporting_period_id, "
                        + "consolidation_approach, notes) VALUES (?, ?, ?, ?) "
                        + "RETURNING " + COLUMNS,
                MAPPER, organizationId, reportingPeriodId, consolidationApproach, notes);
    }

    public int update(String organizationId, String boundaryId,
                      String consolidationApproach, String notes) {
        return jdbc.update(
                "UPDATE organizational_boundaries SET consolidation_approach = ?, notes = ? "
                        + "WHERE id = ? AND organization_id = ?",
                consolidationApproach, notes, boundaryId, organizationId);
    }

    /** Row delete; V1 cascades remove the boundary's facility links. */
    public int delete(String organizationId, String boundaryId) {
        return jdbc.update(
                "DELETE FROM organizational_boundaries WHERE id = ? AND organization_id = ?",
                boundaryId, organizationId);
    }

    /**
     * Persists a boundary ↔ facility pairing only when both sides belong to
     * {@code organizationId}. Returns the number of inserted rows (0 when the
     * facility is not part of this tenant); duplicates hit the V1 primary key
     * and surface as {@code DuplicateKeyException}.
     */
    public int attach(String organizationId, String boundaryId, String facilityId) {
        return jdbc.update(
                "INSERT INTO boundary_facilities (boundary_id, facility_id) "
                        + "SELECT b.id, f.id FROM organizational_boundaries b "
                        + "JOIN facilities f ON f.organization_id = b.organization_id "
                        + "WHERE b.id = ? AND b.organization_id = ? AND f.id = ?",
                boundaryId, organizationId, facilityId);
    }

    public int detach(String organizationId, String boundaryId, String facilityId) {
        return jdbc.update(
                "DELETE FROM boundary_facilities bf "
                        + "USING organizational_boundaries b "
                        + "WHERE bf.boundary_id = b.id AND bf.boundary_id = ? "
                        + "AND bf.facility_id = ? AND b.organization_id = ?",
                boundaryId, facilityId, organizationId);
    }
}
