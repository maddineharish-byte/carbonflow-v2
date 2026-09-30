package com.carbonflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 10.6.1 finding F-01 — the PostgreSQL TLS mode is a real, validated,
 * fail-safe setting rather than a documented fiction.
 *
 * <p>
 * This is a pure unit test: it exercises {@link DataSourceTls} directly, plus
 * the real Spring property-binding and bean-construction path through
 * {@link ApplicationContextRunner}. No database is contacted, so these
 * assertions are about configuration behaviour only. The proof that the value
 * actually reaches PgJDBC lives in {@code DataSourceTlsWiringTest}.
 */
class DataSourceTlsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DataSourceTls.class);

    // --- the configured value is actually consumed ------------------------

    @Test
    void anExplicitlyConfiguredModeIsBoundAndNormalised() {
        runner.withPropertyValues("carbonflow.db.ssl-mode=require")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataSourceTls.class).sslMode()).isEqualTo("require");
                });
    }

    @Test
    void aBlankValueFallsBackToTheDriverDefault() {
        for (String value : new String[] {"carbonflow.db.ssl-mode=", "carbonflow.db.ssl-mode=   "}) {
            runner.withPropertyValues(value)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(DataSourceTls.class).sslMode()).isEqualTo("prefer");
                    });
        }
    }

    @Test
    void anAbsentPropertyFallsBackToTheDriverDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataSourceTls.class).sslMode()).isEqualTo("prefer");
        });
    }

    // --- invalid configuration fails safe, it does not fall back ----------

    @Test
    void aMistypedModeFailsTheContextInsteadOfDowngradingSilently() {
        runner.withPropertyValues("carbonflow.db.ssl-mode=requre")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("requre")
                            .hasMessageContaining("verify-full");
                });
    }

    @Test
    void aPlausibleButWrongModeIsAlsoRejected() {
        // 'enabled' and 'true' are the kind of values an operator reaches for.
        // Silently coercing any of them to a default would re-create exactly the
        // false assurance F-01 is about, so each must be a hard failure.
        for (String bad : new String[] {"enabled", "true", "on", "ssl", "verify", "REQUIRE_FAITH"}) {
            assertThatThrownBy(() -> DataSourceTls.resolveSslMode(bad))
                    .as("%s must be rejected outright", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(bad);
        }
    }

    @Test
    void everyDocumentedPgJdbcModeIsAccepted() {
        for (String mode : DataSourceTls.SUPPORTED_MODES) {
            assertThat(DataSourceTls.resolveSslMode(mode)).isEqualTo(mode);
        }
        assertThat(DataSourceTls.SUPPORTED_MODES)
                .containsExactlyInAnyOrder("disable", "allow", "prefer", "require", "verify-ca", "verify-full");
    }

    @Test
    void valuesAreCaseAndWhitespaceInsensitive() {
        assertThat(DataSourceTls.resolveSslMode("  VERIFY-FULL  ")).isEqualTo("verify-full");
        assertThat(DataSourceTls.resolveSslMode("Require")).isEqualTo("require");
    }

    // --- security semantics are stated honestly ----------------------------

    @Test
    void requireEncryptsButDoesNotVerifyTheServerCertificate() {
        DataSourceTls require = new DataSourceTls("require");
        assertThat(require.forbidsPlaintext())
                .as("require must refuse a plaintext connection").isTrue();
        assertThat(require.verifiesServerCertificate())
                .as("require does NOT verify the server certificate and must not claim to").isFalse();
    }

    @Test
    void onlyVerifyModesClaimCertificateVerification() {
        for (String mode : new String[] {"verify-ca", "verify-full"}) {
            assertThat(new DataSourceTls(mode).verifiesServerCertificate())
                    .as("%s verifies the server certificate", mode).isTrue();
        }
        for (String mode : new String[] {"disable", "allow", "prefer", "require"}) {
            assertThat(new DataSourceTls(mode).verifiesServerCertificate())
                    .as("%s must not claim certificate verification", mode).isFalse();
        }
    }

    @Test
    void plaintextCapableModesDoNotForbidPlaintext() {
        for (String mode : new String[] {"disable", "allow", "prefer"}) {
            assertThat(new DataSourceTls(mode).forbidsPlaintext())
                    .as("%s permits a plaintext connection and must say so", mode).isFalse();
        }
    }

    @Test
    void verifyModesForbidPlaintext() {
        for (String mode : new String[] {"require", "verify-ca", "verify-full"}) {
            assertThat(new DataSourceTls(mode).forbidsPlaintext())
                    .as("%s forbids plaintext", mode).isTrue();
        }
    }

    @Test
    void allowIsNotTreatedAsEnforcingTls() {
        // 'allow' starts in plaintext and only upgrades opportunistically, so it
        // must never be grouped with the mandatory-TLS modes. A regression here
        // would reintroduce exactly the false assurance F-01 is about.
        DataSourceTls allow = new DataSourceTls("allow");
        assertThat(allow.forbidsPlaintext())
                .as("allow does not forbid a plaintext connection").isFalse();
        assertThat(allow.verifiesServerCertificate())
                .as("allow does not verify the server").isFalse();
    }
}
