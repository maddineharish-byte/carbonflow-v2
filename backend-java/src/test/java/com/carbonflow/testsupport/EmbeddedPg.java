package com.carbonflow.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;

/**
 * Starts one hermetic PostgreSQL server per test JVM using zonky
 * embedded-postgres (test scope only — production always uses the real
 * database configured through DB_* environment variables).
 *
 * <p>Integration tests inject the URL through Spring's
 * {@code @DynamicPropertySource}, which overrides the placeholder-based
 * {@code spring.datasource.*} values in the main application.properties.
 */
public final class EmbeddedPg {

    private static EmbeddedPostgres instance;
    private static String jdbcUrl;

    private EmbeddedPg() {
    }

    public static synchronized void init() {
        if (instance != null) {
            return;
        }
        try {
            instance = EmbeddedPostgres.builder().start();
            // stringtype=unspecified: same binding rule as production — String
            // ids must infer as uuid columns (see application.properties).
            jdbcUrl = "jdbc:postgresql://127.0.0.1:" + instance.getPort()
                    + "/postgres?stringtype=unspecified";
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not start embedded PostgreSQL. On first run the binary artifacts "
                            + "(io.zonky.test.postgres:embedded-postgres-binaries-*) are resolved from Maven Central.",
                    e);
        }
    }

    public static String jdbcUrl() {
        init();
        return jdbcUrl;
    }

    public static String username() {
        return "postgres";
    }

    public static String password() {
        return "postgres";
    }
}
