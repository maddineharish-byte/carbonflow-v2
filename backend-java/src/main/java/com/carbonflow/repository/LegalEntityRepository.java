package com.carbonflow.repository;

import com.carbonflow.model.LegalEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code legal_entities} (V1 columns, V4 ownership range
 * check), following Node's {@code listLegalEntities} statement with the
 * tenant predicate on every access.
 *
 * <p><b>Tenant-isolation audit:</b> {@code organization_id = ?} on all reads
 * and writes; inserts stamp the caller's organization. The schema defines no
 * uniqueness on name/registration number, so no duplicate constraint is
 * enforced or invented (ADR-015).
 */
@Repository
public class LegalEntityRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, name, jurisdiction, registration_number, "
                    + "ownership_percentage, created_at, updated_at";

    static final RowMapper<LegalEntity> MAPPER = (rs, rowNum) -> {
        var ownership = rs.getBigDecimal("ownership_percentage");
        return new LegalEntity(
                rs.getString("id"),
                rs.getString("organization_id"),
                rs.getString("name"),
                rs.getString("jurisdiction"),
                rs.getString("registration_number"),
                ownership == null ? null : ownership.doubleValue(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcTemplate jdbc;

    public LegalEntityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Node parity: {@code WHERE organization_id = ? ORDER BY name}. */
    public List<LegalEntity> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM legal_entities WHERE organization_id = ? ORDER BY name",
                MAPPER, organizationId);
    }

    public Optional<LegalEntity> findById(String organizationId, String legalEntityId) {
        List<LegalEntity> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM legal_entities WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, legalEntityId);
        return rows.stream().findFirst();
    }

    public LegalEntity insert(String organizationId, String name, String jurisdiction,
                              String registrationNumber, Double ownershipPercentage) {
        return jdbc.queryForObject(
                "INSERT INTO legal_entities (organization_id, name, jurisdiction, "
                        + "registration_number, ownership_percentage) VALUES (?, ?, ?, ?, ?) "
                        + "RETURNING " + COLUMNS,
                MAPPER, organizationId, name, jurisdiction, registrationNumber, ownershipPercentage);
    }

    public int update(String organizationId, String legalEntityId, String name,
                      String jurisdiction, String registrationNumber, Double ownershipPercentage) {
        return jdbc.update(
                "UPDATE legal_entities SET name = ?, jurisdiction = ?, registration_number = ?, "
                        + "ownership_percentage = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND organization_id = ?",
                name, jurisdiction, registrationNumber, ownershipPercentage,
                legalEntityId, organizationId);
    }

    public int delete(String organizationId, String legalEntityId) {
        return jdbc.update(
                "DELETE FROM legal_entities WHERE id = ? AND organization_id = ?",
                legalEntityId, organizationId);
    }
}
