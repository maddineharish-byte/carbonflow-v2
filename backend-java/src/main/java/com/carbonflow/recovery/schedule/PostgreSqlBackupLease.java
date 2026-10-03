package com.carbonflow.recovery.schedule;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Cross-host scheduling exclusion via a PostgreSQL advisory lock.
 *
 * <h2>Why an advisory lock rather than a lease row</h2>
 * <p>A conventional lease stored in a table needs an owner, an expiry, a renewal
 * loop and a sweeper, and it must still cope correctly with a process that dies
 * without releasing — which is where stale ownership and the "two backups ran
 * anyway" bug come from.
 *
 * <p>A PostgreSQL <b>session-level</b> advisory lock avoids all of that by
 * construction:
 * <ul>
 *   <li>the lock belongs to the <b>session</b>, not to a row;</li>
 *   <li>PostgreSQL releases it the instant the session ends — clean close,
 *       process kill, JVM crash, network drop, or machine loss;</li>
 *   <li>{@code pg_try_advisory_lock} never blocks, so a losing host skips its
 *       cycle immediately instead of queueing a second backup behind the first.</li>
 * </ul>
 *
 * <p>There is therefore <b>no expiry and no renewal</b> to get wrong, and
 * <b>no window in which a dead owner still holds the lock</b>. That is the
 * property that makes this safe to claim as cross-host exclusion.
 *
 * <h2>What this does and does not prove</h2>
 * <p>It excludes concurrent backups across hosts that can all reach the same
 * PostgreSQL instance. It does <b>not</b> coordinate with any other system, and it
 * assumes the scheduler's own database is the one being protected — which is
 * true here, because the backup protects that very database.
 *
 * <p>Two-instance behaviour is proven by
 * {@code BackupLeaseTest.twoInstancesCannotRunConcurrently}, not asserted.
 */
public final class PostgreSqlBackupLease implements AutoCloseable {

    /**
     * Lock namespace.
     *
     * <p>A fixed, project-specific key so the lock cannot collide with another
     * application using advisory locks on the same database. Derived from the
     * project name, not from a value an operator supplies.
     */
    private static final long LOCK_KEY = 0x4341424F4E464C57L; // "CARBONFLW"

    private final Connection connection;
    private boolean held;

    private PostgreSqlBackupLease(Connection connection) {
        this.connection = connection;
    }

    /**
     * Attempts to acquire the cross-host backup lease.
     *
     * @param host     database host
     * @param port     database port
     * @param database database name
     * @param user     role
     * @param password password; may be empty
     * @return a held lease, or {@code null} when another host already holds it
     */
    public static PostgreSqlBackupLease tryAcquire(String host, int port, String database,
                                                   String user, char[] password) {
        Connection connection = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("user", user);
            if (password != null && password.length > 0) {
                properties.setProperty("password", new String(password));
            }
            connection = DriverManager.getConnection(
                    "jdbc:postgresql://" + host + ":" + port + "/" + database, properties);
            // Autocommit must stay on: a session advisory lock is independent of
            // transactions, and keeping autocommit avoids holding a snapshot open
            // for the whole duration of a backup.
            connection.setAutoCommit(true);

            try (Statement statement = connection.createStatement()) {
                boolean acquired = statement.execute(
                        "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")");
                if (!acquired || !statement.getResultSet().next()
                        || !statement.getResultSet().getBoolean(1)) {
                    connection.close();
                    return null;
                }
            }

            PostgreSqlBackupLease lease = new PostgreSqlBackupLease(connection);
            lease.held = true;
            return lease;

        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException(
                    "Could not acquire the cross-host backup lease: " + e.getMessage(), e);
        }
    }

    /**
     * Acquires the lease, treating an unreachable database as "may proceed".
     *
     * <p>This default is deliberate and is the safe direction. If the lock cannot
     * be checked, refusing to back up would mean a database outage also stops
     * backups — precisely when a backup is most valuable. Instead the run
     * proceeds on its local guard and the loss of cross-host exclusion is logged
     * and reported, rather than silently assumed.
     */
    public static PostgreSqlBackupLease tryAcquireOrProceed(String host, int port,
                                                           String database, String user,
                                                           char[] password) {
        try {
            return tryAcquire(host, port, database, user, password);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory
                    .getLogger(PostgreSqlBackupLease.class)
                    .warn("Cross-host backup lease unavailable ({}); proceeding with "
                            + "single-JVM exclusion only. Concurrent backups on another "
                            + "host cannot be ruled out for this run.", e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        if (!held) {
            return;
        }
        held = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (SQLException e) {
            // Closing the session releases the lock regardless, so this is
            // informational only.
            org.slf4j.LoggerFactory.getLogger(PostgreSqlBackupLease.class)
                    .debug("Advisory unlock returned {}; closing the session releases it",
                            e.getMessage());
        }
        closeQuietly(connection);
    }

    /** {@code true} while this lease is held. */
    public boolean isHeld() {
        try {
            return held && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Simulates an owner crashing: the session is torn down without a clean
     * unlock.
     *
     * <p>Exists so the crash case can be proven deterministically in a test. The
     * advisory lock is released by PostgreSQL when the session ends, whether that
     * end was a graceful close or a lost process — which is precisely why no
     * expiry, renewal or sweeper is needed.
     */
    void abandon() {
        held = false;
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }
}