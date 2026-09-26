package com.carbonflow.testsupport;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-fast infrastructure check: the hermetic PostgreSQL must start and
 * support everything db/migration relies on (gen_random_uuid, uuid-ossp,
 * plain DDL). Runs before any Spring context so infrastructure failures are
 * reported as themselves, not as confusing test-context errors.
 */
class EmbeddedPostgresSmokeTest {

    @Test
    void embeddedPostgresRunsAndSupportsMigrationPrerequisites() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                EmbeddedPg.jdbcUrl(), EmbeddedPg.username(), EmbeddedPg.password());
             Statement statement = connection.createStatement()) {

            ResultSet version = statement.executeQuery("SELECT version()");
            assertTrue(version.next());
            assertTrue(version.getString(1).contains("PostgreSQL"),
                    "Expected a real PostgreSQL server, got: " + version.getString(1));

            // pgcrypto builtin (PG13+) used by V1 seeds
            ResultSet uuid = statement.executeQuery("SELECT gen_random_uuid()");
            assertTrue(uuid.next());

            // uuid-ossp extension used by V1 CREATE EXTENSION + uuid_generate_v4
            statement.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            statement.execute("CREATE TABLE smoke_check(id UUID PRIMARY KEY DEFAULT uuid_generate_v4())");
            statement.execute("INSERT INTO smoke_check DEFAULT VALUES");
            statement.execute("DROP TABLE smoke_check");
        }
    }
}
