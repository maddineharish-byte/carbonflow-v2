package com.carbonflow.recovery.postgres;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Connection parameters for a backup, resolved from the same environment
 * variables the application already uses.
 *
 * <h2>Why this does not introduce new configuration</h2>
 * <p>CarbonFlow already reads {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME},
 * {@code DB_USER}, {@code DB_PASSWORD} and {@code DB_SSLMODE}
 * ({@code application.properties}). Reusing them means an operator configures
 * the database once, and the backup tool cannot drift from the application's own
 * view of the server. A separate {@code CARBONFLOW_BACKUP_DB_*} namespace would
 * create a second source of truth that could silently point at a different
 * database than the one being protected.
 *
 * <h2>Password handling</h2>
 * <p>The password is carried in this object but is <b>never</b> rendered into a
 * command line. {@link #toString()} is overridden to omit it, because an
 * accidental string concatenation of a configuration object into a log statement
 * is one of the easiest ways to leak a credential in Java.
 *
 * <p>It reaches {@code pg_dump} only through the {@code PGPASSWORD} environment
 * variable of the child process, which keeps it out of process listings.
 */
public record PostgreSqlBackupTarget(String host, int port, String database,
                                     String username, char[] password,
                                     String sslMode) {

    /** Verifying modes inherited from the application's own TLS semantics. */
    private static final Set<String> VERIFYING_MODES = Set.of("verify-ca", "verify-full");

    /** Modes that permit plaintext, matching {@code DataSourceTls}. */
    private static final Set<String> PLAINTEXT_CAPABLE = Set.of("disable", "allow", "prefer");

    public PostgreSqlBackupTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("DB_HOST is required for a database backup");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("DB_PORT must be a valid TCP port");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("DB_NAME is required for a database backup");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("DB_USER is required for a database backup");
        }
        if (sslMode == null || sslMode.isBlank()) {
            sslMode = "prefer";
        }
        sslMode = sslMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full")
                .contains(sslMode)) {
            // Refuse rather than pass an unrecognised sslmode through to the
            // client tool, which might interpret it differently from PgJDBC.
            throw new IllegalArgumentException(
                    "DB_SSLMODE='" + sslMode + "' is not a supported PostgreSQL sslmode. "
                            + "The backup refuses to guess transport security.");
        }
        password = password == null ? new char[0] : password.clone();
    }

    /**
     * Builds a target from the application's standard environment variables.
     *
     * @param environment process environment
     */
    public static PostgreSqlBackupTarget fromEnvironment(Map<String, String> environment) {
        return new PostgreSqlBackupTarget(
                environment.get("DB_HOST"),
                parsePort(environment.get("DB_PORT")),
                environment.get("DB_NAME"),
                environment.get("DB_USER"),
                toCharArray(environment.get("DB_PASSWORD")),
                environment.getOrDefault("DB_SSLMODE", "prefer"));
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return 5432;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("DB_PORT is not a number: '" + raw + "'", e);
        }
    }

    private static char[] toCharArray(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    /** {@code true} when a password is configured and must be passed via environment. */
    public boolean hasPassword() {
        return password.length > 0;
    }

    /** {@code true} when the effective mode verifies the server certificate. */
    public boolean verifiesServerCertificate() {
        return VERIFYING_MODES.contains(sslMode);
    }

    /** {@code true} when the effective mode permits a plaintext connection. */
    public boolean permitsPlaintext() {
        return PLAINTEXT_CAPABLE.contains(sslMode);
    }

    /**
     * Redacted description.
     *
     * <p>Overrides the record's generated {@code toString} so a password can
     * never reach a log through an interpolated configuration object.
     */
    @Override
    public String toString() {
        return "PostgreSqlBackupTarget[host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", sslMode=" + sslMode
                + ", password=" + (hasPassword() ? "<redacted>" : "<unset>")
                + "]";
    }
}