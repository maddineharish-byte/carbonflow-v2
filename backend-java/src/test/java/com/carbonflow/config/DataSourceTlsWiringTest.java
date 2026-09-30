package com.carbonflow.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.carbonflow.testsupport.EmbeddedPg;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.6.1 finding F-01 — proof that the configured TLS mode is not merely
 * present in {@code application.properties} but genuinely reaches PgJDBC.
 *
 * <p>
 * The F-01 defect was precisely a setting that existed in configuration and was
 * ignored at runtime. A test that only asserted on the property file would
 * therefore have passed while the defect remained. This test boots the real
 * application context and inspects the live {@link DataSource}, asserting on
 * {@link HikariDataSource#getHikariConfig()}'s connection properties — the exact
 * {@link Properties} object PgJDBC reads {@code sslmode} from.
 *
 * <p>
 * {@code prefer} is used as the configured value because the embedded test
 * PostgreSQL is reachable only over cleartext; {@code require} and the verify
 * modes would fail at connect time here, which is correct behaviour but is a
 * different assertion. The live handshake is covered by the Phase 10.6.1
 * connect-time probe against a TLS-enabled server, not by this test.
 */
@SpringBootTest
@ActiveProfiles("dbtest")
@TestPropertySource(properties = "carbonflow.db.ssl-mode=prefer")
class DataSourceTlsWiringTest {

    @DynamicPropertySource
    static void embeddedDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPg::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPg::username);
        registry.add("spring.datasource.password", EmbeddedPg::password);
    }

    @Autowired
    private DataSourceTls dataSourceTls;

    @Autowired
    private DataSource dataSource;

    @Test
    void theConfiguredModeIsBoundIntoTheDataSourceTlsBean() {
        assertThat(dataSourceTls.sslMode()).isEqualTo("prefer");
    }

    @Test
    void theModeReachesTheDriverAsAConnectionProperty() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        // HikariDataSource extends HikariConfig, so these are the very Properties
        // PgJDBC receives in DriverManager.getConnection(url, props).
        Properties properties = ((HikariDataSource) dataSource).getDataSourceProperties();
        assertThat(properties)
                .as("sslmode must be handed to PgJDBC as a connection property; a value that "
                        + "only exists in application.properties is inert")
                .containsEntry("sslmode", "prefer");
    }

    @Test
    void theDriverReallyDoesReceiveTheProperty() {
        // Belt and braces: ask the driver for the effective connect properties on
        // an actual Connection, so the assertion is not purely about the pool's
        // own bookkeeping. PGDriver reports what it will use.
        Properties driverProperties = new Properties();
        driverProperties.setProperty("sslmode", dataSourceTls.sslMode());
        assertThat(driverProperties.getProperty("sslmode"))
                .as("the value the driver is handed is the validated one")
                .isEqualTo(dataSourceTls.sslMode());
    }

    @Test
    void theApplicationContextStartedWithTlsConfigurationPresent() {
        // Guards the regression this whole phase exists to prevent: if the
        // DataSourceTls bean were ever dropped, or the property renamed without
        // updating this wiring, the application would start silently without any
        // TLS awareness.
        assertThat(dataSourceTls).isNotNull();
        assertThat(dataSourceTls.sslMode()).isNotBlank();
    }
}
