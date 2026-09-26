package com.carbonflow.repository;

import com.carbonflow.model.Membership;
import com.carbonflow.model.Organization;
import com.carbonflow.model.User;
import com.carbonflow.model.enums.OrganizationStatus;
import com.carbonflow.model.enums.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Users, memberships and principal loading — the Java port of
 * {@code server/identity-repository.ts} (same SQL semantics: case-insensitive
 * email lookup, memberships joined with roles/organizations/settings, ordered
 * by organization name).
 */
@Repository
public class IdentityRepository {

    public record Principal(User user, List<Membership> memberships) {
    }

    /** Row of {@code GET /users}: a member of the caller's organization. */
    public record OrgMember(String id, String email, String fullName, boolean active, Role role, Instant createdAt) {
    }

    private static final String USER_COLUMNS =
            "u.id::text AS id, u.email AS email, u.password_hash AS password_hash, "
                    + "u.full_name AS full_name, u.is_active AS active, u.created_at AS created_at ";

    private static final String MEMBERSHIP_SELECT =
            "SELECT om.user_id::text AS user_id, om.is_active AS membership_active, r.name AS role_name, "
                    + "o.id::text AS org_id, o.name AS org_name, o.tax_id AS org_tax_id, o.country AS org_country, "
                    + "o.industry AS org_industry, o.status AS org_status, o.created_at AS org_created_at, "
                    + "o.updated_at AS org_updated_at, "
                    + "COALESCE(os.consolidation_approach, 'OPERATIONAL_CONTROL') AS consolidation_approach, "
                    + "COALESCE(os.base_year, 2023) AS base_year "
                    + "FROM organization_memberships om "
                    + "JOIN roles r ON r.id = om.role_id "
                    + "JOIN organizations o ON o.id = om.organization_id "
                    + "LEFT JOIN organization_settings os ON os.organization_id = o.id ";

    private static final RowMapper<User> USER_MAPPER = (rs, rowNum) -> new User(
            rs.getString("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("full_name"),
            rs.getBoolean("active"),
            toInstant(rs, "created_at"));

    private static final RowMapper<Membership> MEMBERSHIP_MAPPER = (rs, rowNum) -> new Membership(
            rs.getString("user_id"),
            new Organization(
                    rs.getString("org_id"),
                    rs.getString("org_name"),
                    rs.getString("org_tax_id"),
                    rs.getString("org_country"),
                    rs.getString("org_industry"),
                    rs.getString("consolidation_approach"),
                    rs.getInt("base_year"),
                    OrganizationStatus.fromDb(rs.getString("org_status")),
                    toInstant(rs, "org_created_at"),
                    toInstant(rs, "org_updated_at")),
            Role.fromDb(rs.getString("role_name")),
            rs.getBoolean("membership_active"));

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    public Optional<User> findUserByEmail(String email) {
        return first(jdbc.query(
                "SELECT " + USER_COLUMNS + "FROM users u WHERE lower(u.email) = lower(?)",
                USER_MAPPER, email));
    }

    public Optional<User> findUserById(String userId) {
        return first(jdbc.query(
                "SELECT " + USER_COLUMNS + "FROM users u WHERE u.id = ?",
                USER_MAPPER, userId));
    }

    public boolean emailExists(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
        return count != null && count > 0;
    }

    public Optional<Principal> loadPrincipalByUserId(String userId) {
        Optional<User> user = findUserById(userId);
        return user.map(u -> new Principal(u, membershipsForUser(userId)));
    }

    public Optional<Principal> loadPrincipalByEmail(String email) {
        Optional<User> user = findUserByEmail(email);
        return user.map(u -> new Principal(u, membershipsForUser(u.getId())));
    }

    public List<Membership> membershipsForUser(String userId) {
        return jdbc.query(
                MEMBERSHIP_SELECT + "WHERE om.user_id = ? ORDER BY o.name",
                MEMBERSHIP_MAPPER, userId);
    }

    /** The single membership used by the per-request tenant validation. */
    public Optional<Membership> findMembership(String userId, String organizationId) {
        return first(jdbc.query(
                MEMBERSHIP_SELECT + "WHERE om.user_id = ? AND om.organization_id = ?",
                MEMBERSHIP_MAPPER, userId, organizationId));
    }

    /** Active memberships of the tenant, ordered by creation (stable for tests). */
    public List<OrgMember> listMembers(String organizationId) {
        return jdbc.query(
                "SELECT u.id::text AS id, u.email AS email, u.full_name AS full_name, "
                        + "u.is_active AS active, r.name AS role_name, u.created_at AS created_at "
                        + "FROM organization_memberships om "
                        + "JOIN users u ON u.id = om.user_id "
                        + "JOIN roles r ON r.id = om.role_id "
                        + "WHERE om.organization_id = ? AND om.is_active = TRUE "
                        + "ORDER BY u.created_at, u.email",
                (rs, rowNum) -> new OrgMember(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getBoolean("active"),
                        Role.fromDb(rs.getString("role_name")),
                        toInstant(rs, "created_at")),
                organizationId);
    }

    /** Returns 0 on any conflict (duplicate id OR duplicate email). */
    public int insertUser(String userId, String email, String passwordHash, String fullName) {
        return jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                userId, email, passwordHash, fullName);
    }

    public int insertMembership(String membershipId, String organizationId, String userId, Role role) {
        return jdbc.update(
                "INSERT INTO organization_memberships (id, organization_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (organization_id, user_id) DO NOTHING",
                membershipId, organizationId, userId, role.uuid());
    }

    public int updateFullName(String userId, String fullName) {
        return jdbc.update(
                "UPDATE users SET full_name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                fullName, userId);
    }

    public int updateMembershipRole(String organizationId, String userId, Role role) {
        return jdbc.update(
                "UPDATE organization_memberships SET role_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND user_id = ?",
                role.uuid(), organizationId, userId);
    }

    public int setUserActive(String userId, boolean active) {
        return jdbc.update(
                "UPDATE users SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                active, userId);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
