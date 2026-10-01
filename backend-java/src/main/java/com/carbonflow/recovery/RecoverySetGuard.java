package com.carbonflow.recovery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * Guards recovery-set metadata against two classes of mistake: filesystem paths
 * that escape the backup set, and values that look like credentials.
 *
 * <p>Both are rejected at <em>write</em> time rather than trusted and filtered
 * later. A manifest is an operational artifact that a human may read during an
 * incident; anything that leaks into it has already escaped.
 */
public final class RecoverySetGuard {

    /**
     * Path segments that must never appear in a manifest.
     *
     * <p>Includes the obvious traversal forms and their platform-specific
     * spellings. Checked case-insensitively because Windows filesystems are
     * case-insensitive and a segment that is innocuous on Linux is not
     * necessarily innocuous on the machine restoring the set.
     */
    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(
            "..", ".", "~", "con", "prn", "aux", "nul");

    /**
     * Absolute-path prefixes that would defeat set-relative resolution.
     *
     * <p>A backup archive is routinely moved between hosts, so a manifest that
     * embeds the original host's absolute root is wrong even when it is not
     * dangerous.
     */
    private static final Set<String> ABSOLUTE_PREFIXES = Set.of(
            "/", "\\", "c:", "d:");

    /**
     * Substrings that mark a value as credential-like.
     *
     * <p>Deliberately broad. A false positive costs a manifest field a rename; a
     * false negative writes a live secret into a file that is copied, retained
     * for 30 days, and — per REC-09 — encrypted with keys held by other people.
     */
    private static final Set<String> SECRET_MARKERS = Set.of(
            "password", "passwd", "secret", "token", "jwt", "credential",
            "passphrase", "apikey", "api_key", "privatekey", "private_key",
            "authorization", "bearer", "sessionid", "cookie");

    private RecoverySetGuard() {
    }

    /**
     * Validates a path recorded inside a recovery set.
     *
     * <p>Accepts only a set-relative POSIX-style path. Rejects traversal
     * ({@code ../../secret}), absolute roots ({@code /etc/shadow},
     * {@code C:\...}), and empty or malformed values.
     *
     * @return the normalised, forward-slash separated relative path
     */
    public static String requireSetRelativePath(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ManifestValidationException.invalid(field, "is required");
        }
        String candidate = value.trim();

        String lowered = candidate.toLowerCase(Locale.ROOT);
        for (String prefix : ABSOLUTE_PREFIXES) {
            if (lowered.startsWith(prefix)) {
                throw ManifestValidationException.invalid(field,
                        "must be relative to the backup set, not an absolute path");
            }
        }
        if (candidate.contains("~")) {
            throw ManifestValidationException.invalid(field,
                    "must not contain a home-directory reference");
        }

        // Normalise separators first so a Windows-style traversal is judged by
        // the same rule as a POSIX one.
        String[] segments = candidate.replace('\\', '/').split("/");
        for (String segment : segments) {
            if (FORBIDDEN_SEGMENTS.contains(segment.trim().toLowerCase(Locale.ROOT))) {
                throw ManifestValidationException.invalid(field,
                        "must not contain a traversal or reserved segment");
            }
        }

        String normalised = String.join("/", segments);
        if (normalised.isBlank() || normalised.startsWith("/")) {
            throw ManifestValidationException.invalid(field, "is not a usable relative path");
        }

        // Final defence: resolving against a base must stay inside that base.
        Path base = Paths.get("set-root-probe");
        Path resolved = base.resolve(normalised).normalize();
        if (!resolved.normalize().startsWith(base)) {
            throw ManifestValidationException.invalid(field, "escapes the backup set");
        }
        return normalised;
    }

    /**
     * Rejects a field name that identifies credential material.
     *
     * <p>The CarbonFlow secrets are named {@code DB_PASSWORD},
     * {@code CARBONFLOW_JWT_SECRET} and {@code CARBONFLOW_REFRESH_TOKEN_SECRET}
     * and belong in the secret manager, never in a backup archive
     * ({@code docs/BACKUP-RECOVERY.md} §1.3). A backup containing them must be
     * handled as a secret store rather than a data archive, which is exactly the
     * confusion this prevents at the earliest possible point.
     */
    public static void requireNonSecretName(String field, String name) {
        if (name == null || name.isBlank()) {
            throw ManifestValidationException.invalid(field, "is required");
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lowered.contains(marker)) {
                throw ManifestValidationException.invalid(name,
                        "names credential material; secrets belong in the secret manager, "
                                + "never in a recovery manifest");
            }
        }
    }
}