package com.carbonflow.repository;

import com.carbonflow.model.ReviewComment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code review_comments} (ADR-009) — the discussion
 * thread plus the server-written transition log.
 *
 * <p><b>Tenant-isolation audit:</b> every statement joins through
 * {@code carbon_audits} and carries {@code a.organization_id = ?}.
 * {@code userName}/{@code userRole} hydrate from the author's active
 * membership in the audit's organization at read time (PostgreSQL stores
 * only {@code user_id}).
 *
 * <p>The Node reference logged transitions and comments without a
 * permission gate (Phase 1 finding); Java gates comment creation on
 * {@code audits.review} per API.md §2.6 — the transition log itself is
 * written server-side, never through this API.
 */
@Repository
public class CommentRepository {

    static final String COLUMNS =
            "rc.id::text, rc.audit_id::text, rc.user_id::text, rc.comment_text, rc.created_at";

    static final RowMapper<ReviewComment> MAPPER = (rs, rowNum) -> {
        ReviewComment comment = new ReviewComment();
        comment.setId(rs.getString("id"));
        comment.setAuditId(rs.getString("audit_id"));
        comment.setUserId(rs.getString("user_id"));
        comment.setCommentText(rs.getString("comment_text"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        comment.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        comment.setUserName(rs.getString("user_email"));
        comment.setUserRole(rs.getString("user_role"));
        return comment;
    };

    /** COLUMNS + author hydration (LEFT JOIN: deleted users keep rows). */
    static final String SELECT =
            "SELECT " + COLUMNS + ", u.email AS user_email, "
                    + "mr.name AS user_role "
                    + "FROM review_comments rc "
                    + "JOIN carbon_audits a ON a.id = rc.audit_id "
                    + "LEFT JOIN users u ON u.id = rc.user_id "
                    + "LEFT JOIN LATERAL ("
                    + "  SELECT r.name FROM organization_memberships m "
                    + "  JOIN roles r ON r.id = m.role_id "
                    + "  WHERE m.user_id = rc.user_id "
                    + "    AND m.organization_id = a.organization_id AND m.is_active = TRUE "
                    + "  ORDER BY m.created_at LIMIT 1"
                    + ") mr ON TRUE";

    private final JdbcTemplate jdbc;

    public CommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReviewComment> listByAudit(String organizationId, String auditId) {
        return jdbc.query(
                SELECT + " WHERE a.organization_id = ? AND rc.audit_id = ? "
                        + "ORDER BY rc.created_at, rc.id",
                MAPPER, organizationId, auditId);
    }

    public Optional<ReviewComment> findById(String organizationId, String auditId,
                                            String commentId) {
        List<ReviewComment> rows = jdbc.query(
                SELECT + " WHERE a.organization_id = ? AND rc.audit_id = ? AND rc.id = ?",
                MAPPER, organizationId, auditId, commentId);
        return rows.stream().findFirst();
    }

    /**
     * Persists the comment, then re-reads it through the tenant-scoped
     * query so the returned row carries the hydrated userName/userRole
     * exactly like every read path (the service has already resolved the
     * audit under the caller's organization).
     */
    public ReviewComment insert(String organizationId, String auditId, String userId,
                                String commentText) {
        ReviewComment comment = jdbc.queryForObject(
                "INSERT INTO review_comments (audit_id, user_id, comment_text) "
                        + "VALUES (?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, user_id::text, "
                        + "comment_text, created_at",
                (rs, rowNum) -> {
                    ReviewComment created = new ReviewComment();
                    created.setId(rs.getString("id"));
                    created.setAuditId(rs.getString("audit_id"));
                    created.setUserId(rs.getString("user_id"));
                    created.setCommentText(rs.getString("comment_text"));
                    java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                    created.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
                    return created;
                },
                auditId, userId, commentText);
        return findById(organizationId, auditId, comment.getId()).orElse(comment);
    }

    public int updateText(String organizationId, String auditId, String commentId,
                          String commentText) {
        return jdbc.update(
                "UPDATE review_comments rc SET comment_text = ? "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = rc.audit_id "
                        + "AND a.organization_id = ? AND rc.audit_id = ? AND rc.id = ?",
                commentText, organizationId, auditId, commentId);
    }

    public int delete(String organizationId, String auditId, String commentId) {
        return jdbc.update(
                "DELETE FROM review_comments rc "
                        + "USING carbon_audits a "
                        + "WHERE a.id = rc.audit_id "
                        + "AND a.organization_id = ? AND rc.audit_id = ? AND rc.id = ?",
                organizationId, auditId, commentId);
    }
}
