package com.carbonflow.recovery.postgres;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Locates the PostgreSQL client executables.
 *
 * <h2>Why discovery is explicit</h2>
 * <p>{@code pg_dump} is frequently <em>not</em> on {@code PATH} even where
 * PostgreSQL is installed — on Windows the installers place the binaries under
 * {@code C:\Program Files\PostgreSQL\<major>\bin} without adding them to the
 * process {@code PATH}, which is the case on the CarbonFlow development host. A
 * backup that assumed {@code pg_dump} was resolvable would fail on exactly the
 * machine an operator is most likely to try first.
 *
 * <p>Resolution order, all provider- and platform-neutral:
 * <ol>
 *   <li>an explicitly configured absolute path (environment variable);</li>
 *   <li>{@code PATH} lookup via the standard resolution rules.</li>
 * </ol>
 *
 * <p>No platform-specific directory is hardcoded. If a configured path does not
 * exist the operator gets a message naming the variable they set, rather than a
 * silent fallback to a different binary — falling back would mean backing up
 * with an unknown version, which is worse than failing.
 */
public final class PostgreSqlToolLocator {

    /** Environment variable naming an explicit {@code pg_dump} executable. */
    public static final String ENV_PG_DUMP = "CARBONFLOW_PG_DUMP";

    /** Environment variable naming an explicit {@code pg_dumpall} executable. */
    public static final String ENV_PG_DUMPALL = "CARBONFLOW_PG_DUMPALL";

    /** Environment variable naming an explicit {@code pg_restore} executable. */
    public static final String ENV_PG_RESTORE = "CARBONFLOW_PG_RESTORE";

    private PostgreSqlToolLocator() {
    }

    /**
     * Resolves one tool.
     *
     * @param toolName       logical name, used only in error messages
     * @param envVariable    variable an operator may set to pin the executable
     * @param bareName       executable name to look for on {@code PATH}
     * @param environment    the environment to read
     * @return the resolved absolute path
     * @throws IllegalStateException if the tool cannot be found, with the
     *                               variable name the operator should set
     */
    public static Path resolve(String toolName, String envVariable, String bareName,
                               java.util.Map<String, String> environment) {
        String configured = environment.get(envVariable);
        if (configured != null && !configured.isBlank()) {
            Path explicit = toPath(configured.trim());
            if (!Files.isRegularFile(explicit)) {
                // Fail loudly. Silently using some other pg_dump would mean
                // restoring with an unrecorded client version.
                throw new IllegalStateException(
                        envVariable + " points at '" + explicit + "', which is not an existing "
                                + "file. Correct the variable or unset it to fall back to PATH "
                                + "lookup for " + toolName + ".");
            }
            return explicit.toAbsolutePath();
        }
        return fromPath(toolName, envVariable, bareName, environment);
    }

    /**
     * {@code PATH} lookup, mirroring how the OS would resolve a bare command.
     *
     * <p>Uses the platform's own separator, so this works on both Windows
     * ({@code ;}) and POSIX ({@code :}) without either being hardcoded.
     */
    private static Path fromPath(String toolName, String envVariable, String bareName,
                                 java.util.Map<String, String> environment) {
        String pathValue = environment.getOrDefault("PATH", "");
        if (pathValue.isBlank()) {
            throw new IllegalStateException(describe(toolName, envVariable, bareName)
                    + " and PATH is not set.");
        }

        List<String> directories = List.of(pathValue.split(java.io.File.pathSeparator));
        for (String directory : directories) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate;
            try {
                candidate = Paths.get(directory).resolve(bareName);
            } catch (InvalidPathException e) {
                // A malformed PATH entry must not abort discovery of the rest.
                continue;
            }
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
            // Windows resolves pg_dump.exe from a bare name; try the suffixed
            // form as well rather than hardcoding a drive layout.
            Path withExtension = Paths.get(directory).resolve(bareName + ".exe");
            if (Files.isRegularFile(withExtension)) {
                return withExtension.toAbsolutePath();
            }
        }
        throw new IllegalStateException(describe(toolName, envVariable, bareName));
    }

    private static String describe(String toolName, String envVariable, String bareName) {
        return "Could not find " + toolName + " ('" + bareName + "') on PATH. "
                + "PostgreSQL client tools are often not on PATH even when the server "
                + "is installed. Set " + envVariable + " to the absolute path of the "
                + "executable (for example the 'bin' directory of your PostgreSQL "
                + "installation) and retry.";
    }

    private static Path toPath(String value) {
        try {
            return Paths.get(value);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Not a usable filesystem path: '" + value + "'", e);
        }
    }

    }