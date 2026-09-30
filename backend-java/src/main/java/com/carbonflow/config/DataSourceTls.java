package com.carbonflow.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails fast when the configured PostgreSQL {@code sslmode} is not a mode this
 * application recognises, so an operator can never believe TLS is configured when
 * the value was silently ignored or mistyped.
 *
 * <p>
 * <b>Why this exists (F-01, Phase 10.6.1).</b> Before this class the application
 * built its JDBC URL with no {@code sslmode} at all, so the driver silently
 * applied its own default ({@code prefer}, which downgrades to plaintext when
 * the server offers no TLS) and the deployment documentation described controls
 * that did not exist. A typo such as {@code DB_SSLMODE=requre} must therefore be
 * a startup failure, not a quiet downgrade.
 *
 * <p>
 * <b>What each mode actually guarantees.</b> {@code require} encrypts the
 * transport but does <em>not</em> verify the server certificate, so it protects
 * against passive interception only and is <em>not</em> proof of server
 * identity. {@code verify-ca} and {@code verify-full} are the only modes that
 * verify the server's certificate against a trusted authority. This class
 * deliberately does not treat them as equivalent.
 */
@Component
public class DataSourceTls {

    private static final Logger log = LoggerFactory.getLogger(DataSourceTls.class);

    /** The PgJDBC {@code sslmode} values, in the driver's own documented order. */
    public static final Set<String> SUPPORTED_MODES = new LinkedHashSet<>(Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full"));

    /** Modes that verify the server certificate against a trusted authority. */
    public static final Set<String> VERIFYING_MODES = Set.of("verify-ca", "verify-full");

    private final String sslMode;

    public DataSourceTls(@Value("${carbonflow.db.ssl-mode:prefer}") String configured) {
        this.sslMode = resolveSslMode(configured);
        if ("prefer".equals(sslMode)) {
            log.warn("carbonflow.db.ssl-mode is 'prefer': the connection attempts TLS but will "
                    + "silently fall back to plaintext if the server does not offer TLS. "
                    + "Set DB_SSLMODE=require (transport encryption) or verify-ca/verify-full "
                    + "(certificate verification) for production.");
        } else if ("disable".equals(sslMode)) {
            log.warn("carbonflow.db.ssl-mode is 'disable': database traffic is plaintext. "
                    + "Acceptable on a trusted local network only.");
        } else if ("allow".equals(sslMode)) {
            log.warn("carbonflow.db.ssl-mode is 'allow': the connection starts in plaintext and "
                    + "only upgrades to TLS if the server offers it, so it may be unencrypted and "
                    + "the server is never verified. Set DB_SSLMODE=require or verify-ca for production.");
        } else if (VERIFYING_MODES.contains(sslMode)) {
            log.info("carbonflow.db.ssl-mode is '{}': TLS is mandatory and the server certificate "
                    + "is verified against the JVM trust store.", sslMode);
        } else {
            log.info("carbonflow.db.ssl-mode is '{}': TLS is mandatory, but the server certificate "
                    + "is NOT verified — this encrypts the transport without proving server identity.",
                    sslMode);
        }
    }

    /**
     * Normalises and validates a configured {@code sslmode}.
     *
     * <p>
     * A blank or {@code null} value is treated as "not configured" and falls back
     * to the driver's own default, {@code prefer}. Any other unrecognised value
     * throws so the application context fails instead of starting with a
     * silently weakened or ignored setting.
     *
     * @param configured the raw configured value; may be {@code null}
     * @return the normalised, supported {@code sslmode}
     * @throws IllegalStateException if the value is present but unsupported
     */
    public static String resolveSslMode(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return "prefer";
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(normalized)) {
            throw new IllegalStateException(
                    "carbonflow.db.ssl-mode (DB_SSLMODE) is '" + configured.trim() + "' which is not a "
                            + "supported PostgreSQL sslmode. Supported values are "
                            + String.join(", ", SUPPORTED_MODES) + ". The application refuses to start "
                            + "rather than fall back to an unintended transport security level.");
        }
        return normalized;
    }

    /** @return the validated, normalised {@code sslmode} in effect. */
    public String sslMode() {
        return sslMode;
    }

    /**
     * @return {@code true} when the effective mode verifies the server certificate.
     *         {@code require} returns {@code false}: it encrypts but does not verify.
     */
    public boolean verifiesServerCertificate() {
        return VERIFYING_MODES.contains(sslMode);
    }

    /**
     * @return {@code true} when the mode forbids any plaintext connection, so a
     *         misconfigured or TLS-less server fails the connection rather than
     *         silently downgrading.
     */
    public boolean forbidsPlaintext() {
        return !"disable".equals(sslMode) && !"allow".equals(sslMode) && !"prefer".equals(sslMode);
    }
}
