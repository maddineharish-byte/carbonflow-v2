package com.carbonflow.recovery.validation;

import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REC-11 — measures actual committed-data loss against the recovery boundary.
 *
 * <h2>Method</h2>
 * <pre>
 *   write committed marker BEFORE the boundary
 *       → take a backup (boundary B)
 *       → write committed marker AFTER the boundary
 *       → declare a qualifying failure (destroy the source database)
 *       → restore set B
 *       → assert: the pre-boundary marker survived
 *       → assert: the post-boundary marker was lost
 * </pre>
 *
 * <h2>Why both markers matter</h2>
 * <p>Only the first assertion measures the RPO. A marker committed at or before
 * the boundary must survive, because that data is inside the recovery point. The
 * second marker proves the boundary is <em>real</em> rather than the restore
 * silently seeing later data; losing it is correct behaviour and is recorded as
 * {@code correctlyLost}, not as a failure.
 *
 * <h2>What is deliberately not claimed</h2>
 * <p>A zero committed loss proves the boundary was exact at this instant. It does
 * not prove the RPO holds generally: worst-case loss is bounded by the backup
 * interval, which is recorded separately rather than implied away.
 */
public final class RpoValidator {

    /** Table used to hold markers. Created by the caller, dropped afterwards. */
    private static final String MARKER_TABLE = "recovery_rpo_marker";

    /** One identifiable row written into the source database. */
    public record Marker(String markerId, Instant writtenAt, boolean committedBeforeBoundary) {
    }

    /**
     * Records when a marker was committed. The instant is taken by the caller so
     * the boundary comparison uses the same clock as the backup.
     */
    public String writeMarker(Connection connection, String table, Instant at)
            throws SQLException {
        String id = "RPO-" + UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id VARCHAR(64) PRIMARY KEY, written_at TIMESTAMPTZ NOT NULL, "
                    + "payload VARCHAR(64) NOT NULL)");
            statement.execute("INSERT INTO " + table
                    + " (id, written_at, payload) VALUES ('" + id + "', '"
                    + java.sql.Timestamp.from(at) + "', 'CARBONFLOW-RPO-MARKER')");
        }
        return id;
    }

    /** Creates the marker table. */
    public void createMarkerTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + MARKER_TABLE + " ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "written_at TIMESTAMPTZ NOT NULL, "
                    + "payload VARCHAR(64) NOT NULL)");
        }
    }

    /**
     * Whether a marker survived in a restored database.
     *
     * @return {@code true} when the row is present
     */
    public boolean markerSurvived(Connection connection, String markerId)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select count(*) from " + MARKER_TABLE + " where id = '"
                             + markerId.replace("'", "''") + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** Drops the marker table. */
    public void dropMarkerTable(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + MARKER_TABLE);
        } catch (SQLException ignored) {
            // best effort
        }
    }

    /**
     * Assembles the result from the observed facts.
     *
     * <p>Separated from the mechanics so the pass/fail logic can be tested
     * directly against synthetic observations rather than requiring a live
     * database.
     *
     * @param beforeBoundarySurvived the pre-boundary marker survived
     * @param afterBoundarySurvived  the post-boundary marker also survived, which
     *                               would mean the boundary was not real
     */
    public RpoValidationResult assess(String backupSetId, Instant boundaryAt,
                                      Instant failureAt, Instant beforeMarkerAt,
                                      Instant afterMarkerAt,
                                      boolean beforeBoundarySurvived,
                                      boolean afterBoundarySurvived,
                                      java.time.Duration restoreDuration,
                                      java.time.Duration backupInterval,
                                      String environment) {

        List<RpoValidationResult.MarkerOutcome> markers = new ArrayList<>();

        // The decisive assertion: data committed at or before the boundary is
        // inside the recovery point and must not be lost.
        markers.add(beforeBoundarySurvived
                ? RpoValidationResult.MarkerOutcome.retained("BEFORE", beforeMarkerAt)
                : new RpoValidationResult.MarkerOutcome("BEFORE", beforeMarkerAt,
                        "BEFORE_BOUNDARY", false, false));

        // A post-boundary marker surviving means the restore saw data newer than
        // its own boundary, so the measurement cannot be trusted.
        markers.add(afterBoundarySurvived
                ? new RpoValidationResult.MarkerOutcome("AFTER", afterMarkerAt,
                        "AFTER_BOUNDARY", true, false)
                : RpoValidationResult.MarkerOutcome.lostAfterBoundary("AFTER",
                        afterMarkerAt));

        boolean boundaryExact = beforeBoundarySurvived && !afterBoundarySurvived;

        // Measured committed loss is zero when everything inside the boundary
        // survived. It is NOT restore duration.
        java.time.Duration committedLoss = boundaryExact
                ? java.time.Duration.ZERO
                : java.time.Duration.between(boundaryAt, failureAt);

        java.time.Duration worstCase = backupInterval == null
                ? java.time.Duration.ZERO : backupInterval;

        boolean targetMet = committedLoss.compareTo(RpoValidationResult.APPROVED_RPO) <= 0;

        return new RpoValidationResult(
                boundaryExact,
                backupSetId,
                boundaryAt,
                failureAt,
                committedLoss,
                worstCase,
                restoreDuration,
                targetMet,
                markers,
                RpoValidationResult.standardLimitations(),
                environment);
    }

    /** Opens a connection to a specific database on a target server. */
    public Connection connect(PostgreSqlBackupTarget target, String database)
            throws SQLException {
        try {
            return DriverManager.getConnection(
                    "jdbc:postgresql://" + target.host() + ":" + target.port()
                            + "/" + database,
                    target.username(), new String(target.password()));
        } catch (SQLException e) {
            throw new SQLException("could not connect to database '" + database
                    + "': " + e.getMessage(), e);
        }
    }

    /** Marker table name, exposed so a harness can reason about it. */
    public String markerTable() {
        return MARKER_TABLE;
    }
}