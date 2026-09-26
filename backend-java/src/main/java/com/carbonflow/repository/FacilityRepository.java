package com.carbonflow.repository;

import com.carbonflow.model.Facility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code facilities} (ADR-009 — no ORM), ported from
 * {@code server/scope-repository.ts} with the tenant predicate promoted to
 * every statement.
 *
 * <p><b>Tenant-isolation audit:</b> every read and write carries
 * {@code organization_id = ?} sourced from the authenticated tenant context,
 * never from a client-supplied id — a row of another organization is
 * invisible to {@code findById}/{@code update}/{@code delete}, and inserts
 * stamp the caller's organization id. The V4 unique index
 * {@code (organization_id, facility_code)} scopes duplicates per tenant.
 */
@Repository
public class FacilityRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, legal_entity_id::text, name, facility_code, "
                    + "facility_type, country, state_province, grid_region, floor_area_m2, "
                    + "created_at, updated_at";

    static final RowMapper<Facility> MAPPER = (rs, rowNum) -> {
        var floorArea = rs.getBigDecimal("floor_area_m2");
        return new Facility(
                rs.getString("id"),
                rs.getString("organization_id"),
                rs.getString("legal_entity_id"),
                rs.getString("name"),
                rs.getString("facility_code"),
                rs.getString("facility_type"),
                rs.getString("country"),
                rs.getString("state_province"),
                rs.getString("grid_region"),
                floorArea == null ? null : floorArea.doubleValue(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcTemplate jdbc;

    public FacilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Node parity: {@code WHERE organization_id = ? ORDER BY name}. */
    public List<Facility> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM facilities WHERE organization_id = ? ORDER BY name",
                MAPPER, organizationId);
    }

    public Optional<Facility> findById(String organizationId, String facilityId) {
        List<Facility> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM facilities WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, facilityId);
        return rows.stream().findFirst();
    }

    public Facility insert(String organizationId, String legalEntityId, String name,
                           String facilityCode, String facilityType, String country,
                           String stateProvince, String gridRegion, Double floorAreaM2) {
        return jdbc.queryForObject(
                "INSERT INTO facilities (organization_id, legal_entity_id, name, facility_code, "
                        + "facility_type, country, state_province, grid_region, floor_area_m2) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "RETURNING " + COLUMNS,
                MAPPER, organizationId, legalEntityId, name, facilityCode, facilityType,
                country, stateProvince, gridRegion, floorAreaM2);
    }

    /** Full-replace update (PUT semantics); tenant-guarded. */
    public int update(String organizationId, String facilityId, String legalEntityId,
                      String name, String facilityCode, String facilityType, String country,
                      String stateProvince, String gridRegion, Double floorAreaM2) {
        return jdbc.update(
                "UPDATE facilities SET legal_entity_id = ?, name = ?, facility_code = ?, "
                        + "facility_type = ?, country = ?, state_province = ?, grid_region = ?, "
                        + "floor_area_m2 = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND organization_id = ?",
                legalEntityId, name, facilityCode, facilityType, country, stateProvince,
                gridRegion, floorAreaM2, facilityId, organizationId);
    }

    /** Tenant-guarded delete; RESTRICT references (activity data) surface as FK errors. */
    public int delete(String organizationId, String facilityId) {
        return jdbc.update(
                "DELETE FROM facilities WHERE id = ? AND organization_id = ?",
                facilityId, organizationId);
    }
}
