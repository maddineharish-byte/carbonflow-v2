package com.carbonflow.recovery;

import java.util.regex.Pattern;

/**
 * Raised when recovery-set metadata cannot be accepted.
 *
 * <p>A plain unchecked exception on purpose: every call site is operational
 * tooling (a backup job, a verifier, a drill), not a user request. There is no
 * HTTP status to map and no client to protect, and wrapping it in the
 * application envelope would imply the failure is part of request handling.
 *
 * <p>The CarbonFlow application does not reference this class, so a manifest
 * problem can never become a runtime API failure.
 */
public class ManifestValidationException extends RuntimeException {

    public ManifestValidationException(String message) {
        super(message);
    }

    /**
     * Rejects a field value that is present but not usable.
     *
     * <p>The message names the field and echoes nothing sensitive — recovery
     * metadata is rejected loudly rather than logged and swallowed.
     */
    public static ManifestValidationException invalid(String field, String reason) {
        return new ManifestValidationException(field + " " + reason);
    }

    /**
     * Hex SHA-256 digest: exactly 64 lowercase hex characters.
     *
     * <p>Checked rather than assumed. A typo'd or truncated checksum is the
     * exact failure mode a manifest exists to prevent, and a validator that
     * accepted any string would convert that failure into a false assurance.
     */
    public static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** Validates a SHA-256 hex digest supplied by a backup producer. */
    public static String requireSha256Hex(String field, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "is required");
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SHA256_HEX.matcher(trimmed).matches()) {
            throw invalid(field, "must be 64 hexadecimal characters (SHA-256)");
        }
        return trimmed;
    }
}