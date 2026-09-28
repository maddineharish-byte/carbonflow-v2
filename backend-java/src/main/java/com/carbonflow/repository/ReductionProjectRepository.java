package com.carbonflow.repository;

import com.carbonflow.model.ReductionProject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code reduction_projects} (ADR-009 — no ORM; V1 columns
 * incl. nullable SET NULL FKs and DATE pair). Every statement predicates
 * {@code organization_id} from the authenticated tenant context; listing is
 * {@code created_at DESC, id DESC} (newest first, deterministic tiebreak —
 * documented; Node returned insertion order).
 *
 * <p>Created in Phase 7 for dashboard counts; extended with the full project
 * queries in task 7.5.
 */
@Repository
public class ReductionProjectRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, target_id::text, facility_id::text, name, "
                    + "description, baseline_t, expected_reduction_t, actual_reduction_t, "
                    + "start_date, end_date, status, owner_id::text, created_at";

    static final RowMapper<ReductionProject> MAPPER = (rs, rowNum) -> new ReductionProject(
            rs.getString("id"),
            rs.getString("organization_id"),
            rs.getString("target_id"),
            rs.getString("facility_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBigDecimal("baseline_t"),
            rs.getBigDecimal("expected_reduction_t"),
            rs.getBigDecimal("actual_reduction_t"),
            rs.getDate("start_date").toLocalDate(),
            rs.getDate("end_date").toLocalDate(),
            rs.getString("status"),
            rs.getString("owner_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public ReductionProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code GET /analytics/dashboard} reduction-projects counter. */
    public int count(String organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reduction_projects WHERE organization_id = ?",
                Integer.class, organizationId);
        return count == null ? 0 : count;
    }

    public List<ReductionProject> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM reduction_projects WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER, organizationId);
    }

    public Optional<ReductionProject> findById(String organizationId, String projectId) {
        List<ReductionProject> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reduction_projects "
                        + "WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, projectId);
        return rows.stream().findFirst();
    }

    public ReductionProject insert(String organizationId, String targetId, String facilityId,
                                   String name, String description, BigDecimal baselineT,
                                   BigDecimal expectedReductionT, BigDecimal actualReductionT,
                                   java.time.LocalDate startDate, java.time.LocalDate endDate,
                                   String status, String ownerId) {
        return jdbc.queryForObject(
                "INSERT INTO reduction_projects "
                        + "(organization_id, target_id, facility_id, name, description, "
                        + " baseline_t, expected_reduction_t, actual_reduction_t, "
                        + " start_date, end_date, status, owner_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, organizationId, targetId, facilityId, name, description,
                baselineT, expectedReductionT, actualReductionT, startDate, endDate,
                status, ownerId);
    }

    /**
     * Full mutable-state update — {@code owner_id}, {@code created_at} and the
     * tenant are untouched by this statement (immutable identity fields).
     */
    public int update(String organizationId, String projectId, String targetId,
                      String facilityId, String name, String description, BigDecimal baselineT,
                      BigDecimal expectedReductionT, BigDecimal actualReductionT,
                      java.time.LocalDate startDate, java.time.LocalDate endDate,
                      String status) {
        return jdbc.update(
                "UPDATE reduction_projects SET target_id = ?, facility_id = ?, name = ?, "
                        + "description = ?, baseline_t = ?, expected_reduction_t = ?, "
                        + "actual_reduction_t = ?, start_date = ?, end_date = ?, status = ? "
                        + "WHERE organization_id = ? AND id = ?",
                targetId, facilityId, name, description, baselineT, expectedReductionT,
                actualReductionT, startDate, endDate, status, organizationId, projectId);
    }
}
