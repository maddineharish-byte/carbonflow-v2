package com.carbonflow.repository;

import com.carbonflow.model.Organization;
import com.carbonflow.model.User;
import com.carbonflow.model.enums.OrganizationStatus;
import com.carbonflow.model.enums.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * {@code refresh_tokens} persistence — the Java port of
 * {@code server/postgres-refresh-repository.ts} (ADR-007 / ADR-014): opaque
 * 64-hex tokens are stored only as HMAC-SHA256 hashes, rotation happens under
 * a row lock ({@code SELECT ... FOR UPDATE}) inside the caller's transaction,
 * and families are revoked atomically on logout or on replay detection.
 */
@Repository
public class RefreshTokenRepository {

    /**
     * The locked refresh row plus the identity context it authorizes.
     * {@code membershipActive} is false when the membership row is missing
     * entirely (LEFT JOIN) or explicitly inactive.
     */
    public record LockedToken(
            String tokenId,
            String familyId,
            Instant expiresAt,
            boolean revoked,
            String replacedByTokenId,
            User user,
            Organization organization,
            Role role,
            boolean membershipActive) {
    }

    /**
     * Same join shape as the Node implementation's {@code PersistedRefreshContext}.
     * <p><strong>Must be called inside an active transaction</strong> —
     * {@code FOR UPDATE} silently takes no lock outside one, and rotation
     * atomicity depends on it (the surrounding service method is
     * {@code @Transactional}).
     */
    private static final String LOCK_SELECT =
            "SELECT rt.id::text AS token_id, rt.family_id::text AS family_id, rt.expires_at AS expires_at, "
                    + "rt.revoked_at AS revoked_at, rt.replaced_by_token_id::text AS replaced_by, "
                    + "u.id::text AS user_id, u.email AS email, u.password_hash AS password_hash, "
                    + "u.full_name AS full_name, u.is_active AS user_active, u.created_at AS user_created_at, "
                    + "o.id::text AS org_id, o.name AS org_name, o.tax_id AS org_tax_id, o.country AS org_country, "
                    + "o.industry AS org_industry, o.status AS org_status, o.created_at AS org_created_at, "
                    + "o.updated_at AS org_updated_at, "
                    + "COALESCE(os.consolidation_approach, 'OPERATIONAL_CONTROL') AS consolidation_approach, "
                    + "COALESCE(os.base_year, 2023) AS base_year, "
                    + "r.name AS role_name, om.is_active AS membership_active "
                    + "FROM refresh_tokens rt "
                    + "JOIN users u ON u.id = rt.user_id "
                    + "JOIN organizations o ON o.id = rt.organization_id "
                    + "LEFT JOIN organization_settings os ON os.organization_id = o.id "
                    + "JOIN roles r ON r.id = rt.role_id "
                    + "LEFT JOIN organization_memberships om ON om.user_id = rt.user_id "
                    + "AND om.organization_id = rt.organization_id AND om.role_id = rt.role_id "
                    + "WHERE rt.token_hash = ? "
                    + "FOR UPDATE OF rt";

    private static final RowMapper<LockedToken> LOCKED_TOKEN_MAPPER = (rs, rowNum) -> {
        boolean membershipActive = rs.getBoolean("membership_active");
        boolean membershipMissing = rs.wasNull();
        return new LockedToken(
                rs.getString("token_id"),
                rs.getString("family_id"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("revoked_at") != null,
                rs.getString("replaced_by"),
                new User(
                        rs.getString("user_id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getBoolean("user_active"),
                        rs.getObject("user_created_at", OffsetDateTime.class).toInstant()),
                new Organization(
                        rs.getString("org_id"),
                        rs.getString("org_name"),
                        rs.getString("org_tax_id"),
                        rs.getString("org_country"),
                        rs.getString("org_industry"),
                        rs.getString("consolidation_approach"),
                        rs.getInt("base_year"),
                        OrganizationStatus.fromDb(rs.getString("org_status")),
                        rs.getObject("org_created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("org_updated_at", OffsetDateTime.class).toInstant()),
                Role.fromDb(rs.getString("role_name")),
                !membershipMissing && membershipActive);
    };

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the row for the given hash. {@code membershipActive} uses
     * {@code getObject} + {@code wasNull}: a missing membership (LEFT JOIN
     * produced NULL) must read as inactive, never as {@code true}.
     */
    public Optional<LockedToken> lockByTokenHash(String tokenHash) {
        return jdbc.query(LOCK_SELECT, LOCKED_TOKEN_MAPPER, tokenHash).stream().findFirst();
    }

    public int insertToken(String tokenId, String userId, String organizationId, Role role,
                           String familyId, String tokenHash, Instant expiresAt) {
        return jdbc.update(
                "INSERT INTO refresh_tokens (id, user_id, organization_id, role_id, family_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                tokenId, userId, organizationId, role.uuid(), familyId, tokenHash, expiresAt.toString());
    }

    /** Marks the old row revoked and links its replacement (V3 constraints). */
    public int markRotated(String oldTokenId, String newTokenId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ?, replaced_by_token_id = ? WHERE id = ?",
                revokedAt.toString(), newTokenId, oldTokenId);
    }

    public int revokeToken(String tokenId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                revokedAt.toString(), tokenId);
    }

    /** Revokes every not-yet-revoked token of the family (logout / replay). */
    public int revokeFamily(String familyId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                revokedAt.toString(), familyId);
    }
}
