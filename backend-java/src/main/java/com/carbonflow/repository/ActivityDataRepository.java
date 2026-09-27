package com.carbonflow.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Minimal {@code activity_data} access for Phase 5 scope validation only —
 * correction requests reference activity records ({@code
 * correction_requests.activity_data_id NOT NULL}) and evidence links may
 * target {@code ACTIVITY_DATA}. The activity-data module itself migrates in
 * Phase 6; Phase 5 never creates or mutates rows through this repository
 * (tests insert fixtures directly, as Phase 4 did for RESTRICT proofs).
 */
@Repository
public class ActivityDataRepository {

    private final JdbcTemplate jdbc;

    public ActivityDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tenant-scoped existence check — never a bare {@code WHERE id = ?}. */
    public boolean exists(String organizationId, String activityDataId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data WHERE organization_id = ? AND id = ?",
                Integer.class, organizationId, activityDataId);
        return count != null && count > 0;
    }
}
