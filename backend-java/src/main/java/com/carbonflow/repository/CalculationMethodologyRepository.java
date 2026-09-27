package com.carbonflow.repository;

import com.carbonflow.model.CalculationMethodology;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code calculation_methodologies} (ADR-009 — no ORM).
 *
 * <p>Reference-only catalogue (established ADR): the engine takes no
 * methodology input and {@code calculations} has no methodology column — this
 * repository exists so the methodology identity/version can be read as
 * provenance reference data, nothing more. The table carries no
 * {@code organization_id}: it is platform-global like the other reference
 * tables, so a tenant predicate would be meaningless here.
 */
@Repository
public class CalculationMethodologyRepository {

    private static final String COLUMNS = "id, code, name, version, description";

    private static final RowMapper<CalculationMethodology> MAPPER = (rs, rowNum) -> {
        CalculationMethodology methodology = new CalculationMethodology();
        methodology.setId(rs.getString("id"));
        methodology.setCode(rs.getString("code"));
        methodology.setName(rs.getString("name"));
        methodology.setVersion(rs.getString("version"));
        methodology.setDescription(rs.getString("description"));
        return methodology;
    };

    private final JdbcTemplate jdbc;

    public CalculationMethodologyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CalculationMethodology> list() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calculation_methodologies ORDER BY code",
                MAPPER);
    }

    public Optional<CalculationMethodology> findById(String methodologyId) {
        List<CalculationMethodology> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM calculation_methodologies WHERE id = ?",
                MAPPER, methodologyId);
        return rows.stream().findFirst();
    }
}
