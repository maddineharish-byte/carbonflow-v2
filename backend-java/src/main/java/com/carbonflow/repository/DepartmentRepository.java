package com.carbonflow.repository;

import com.carbonflow.model.Department;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code departments} (V1: department belongs to a facility,
 * {@code facility_id NOT NULL}).
 *
 * <p><b>Tenant-isolation audit:</b> every statement carries
 * {@code organization_id = ?}; the optional {@code facilityId} filter is an
 * additional predicate, never a replacement for the tenant one — a query for
 * another tenant's facility id yields nothing rather than that tenant's
 * departments. Parent-facility ownership is validated separately through
 * {@code ScopeService} before any insert.
 */
@Repository
public class DepartmentRepository {

    static final String COLUMNS = "id::text, organization_id::text, facility_id::text, name, created_at";

    static final RowMapper<Department> MAPPER = (rs, rowNum) -> new Department(
            rs.getString("id"),
            rs.getString("organization_id"),
            rs.getString("facility_id"),
            rs.getString("name"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public DepartmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Department> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM departments WHERE organization_id = ? ORDER BY name",
                MAPPER, organizationId);
    }

    public List<Department> listByFacility(String organizationId, String facilityId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM departments WHERE organization_id = ? AND facility_id = ? ORDER BY name",
                MAPPER, organizationId, facilityId);
    }

    public Optional<Department> findById(String organizationId, String departmentId) {
        List<Department> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM departments WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, departmentId);
        return rows.stream().findFirst();
    }

    public Department insert(String organizationId, String facilityId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO departments (organization_id, facility_id, name) VALUES (?, ?, ?) "
                        + "RETURNING " + COLUMNS,
                MAPPER, organizationId, facilityId, name);
    }

    public int update(String organizationId, String departmentId, String facilityId, String name) {
        return jdbc.update(
                "UPDATE departments SET facility_id = ?, name = ? "
                        + "WHERE id = ? AND organization_id = ?",
                facilityId, name, departmentId, organizationId);
    }

    public int delete(String organizationId, String departmentId) {
        return jdbc.update(
                "DELETE FROM departments WHERE id = ? AND organization_id = ?",
                departmentId, organizationId);
    }
}
