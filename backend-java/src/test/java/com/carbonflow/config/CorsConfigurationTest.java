package com.carbonflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 10.4.1 finding 5 — the CORS allow-list is deployment-owned.
 *
 * <p>These tests exercise the exact production factory
 * ({@link SecurityConfig#corsConfigurationSource(String)}) with the raw property
 * values an operator can supply, without booting a Spring context:
 *
 * <ul>
 *   <li>an explicitly configured production origin is allowed;</li>
 *   <li>an unconfigured origin is not in the list;</li>
 *   <li>the shipped default is empty, so no localhost origin is trusted
 *       implicitly in production;</li>
 *   <li>localhost is available when — and only when — a development value
 *       configures it;</li>
 *   <li>credentials stay on and only for listed origins;</li>
 *   <li>several origins can be listed, so one build serves many deployments;</li>
 *   <li>{@code *} is refused, because the API always allows credentials.</li>
 * </ul>
 */
class CorsConfigurationTest {

    private static CorsConfiguration configFor(String rawValue) {
        CorsConfigurationSource source = SecurityConfig.corsConfigurationSource(rawValue);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/facilities");
        CorsConfiguration config = source.getCorsConfiguration(request);
        assertNotNull(config, "a CORS configuration must be registered for the API surface");
        return config;
    }

    private static boolean allows(CorsConfiguration config, String origin) {
        return config.checkOrigin(origin) != null;
    }

    // ------------------------------------------------------------------
    // 1. Allowed configured production origin
    // ------------------------------------------------------------------

    @Test
    void explicitlyConfiguredProductionOriginIsAllowed() {
        CorsConfiguration config = configFor("https://carbon.example.com");

        assertEquals(List.of("https://carbon.example.com"), config.getAllowedOrigins());
        assertTrue(allows(config, "https://carbon.example.com"),
                "the configured production origin must be allowed");
    }

    // ------------------------------------------------------------------
    // 2. Unconfigured origin
    // ------------------------------------------------------------------

    @Test
    void unconfiguredOriginIsNotAllowed() {
        CorsConfiguration config = configFor("https://carbon.example.com");

        assertFalse(allows(config, "https://evil.example"),
                "an origin nobody configured must not be allowed");
        assertFalse(allows(config, "http://localhost:5173"),
                "an unlisted localhost origin must not be allowed either");
    }

    // ------------------------------------------------------------------
    // 3. localhost is not trusted in the production default
    // ------------------------------------------------------------------

    @Test
    void productionDefaultTrustsNoOriginAtAll() {
        // The value application.properties ships when the operator sets nothing.
        CorsConfiguration empty = configFor("");
        assertTrue(empty.getAllowedOrigins().isEmpty(),
                "the shipped default must be an empty allow-list (fail-closed)");

        // The retired Node/Express origin specifically.
        CorsConfiguration legacy = configFor("https://carbon.example.com");
        assertFalse(allows(legacy, "http://localhost:3000"),
                "http://localhost:3000 must not be trusted by a production allow-list");
        assertFalse(allows(legacy, "http://localhost:5173"),
                "http://localhost:5173 must not be trusted by a production allow-list");

        // ...and a literal localhost:3000 in the list is a configuration error
        // only in the sense that the operator asked for it: it is honoured, so
        // the guardrail is "explicit", not "impossible".
        assertFalse(configFor("").getAllowedOrigins().contains("http://localhost:3000"));
    }

    // ------------------------------------------------------------------
    // 4. localhost in a development configuration
    // ------------------------------------------------------------------

    @Test
    void developmentValueAllowsTheLocalhostDevServerOrigins() {
        // Exactly what application-dev.properties carries.
        CorsConfiguration dev = configFor("http://localhost:5173,http://localhost:3000");

        assertTrue(allows(dev, "http://localhost:5173"),
                "the Vite dev server origin must be allowed in the dev profile");
        assertTrue(allows(dev, "http://localhost:3000"),
                "localhost development is preserved, not removed globally");
        assertFalse(allows(dev, "https://evil.example"),
                "the dev profile still refuses unlisted origins");
    }

    // ------------------------------------------------------------------
    // 5. Credentials
    // ------------------------------------------------------------------

    @Test
    void credentialsAreAllowedOnlyForListedOrigins() {
        CorsConfiguration dev = configFor("http://localhost:5173");

        assertTrue(dev.getAllowCredentials(),
                "the React client authenticates cross-origin, credentials stay enabled");
        assertEquals("http://localhost:5173",
                dev.checkOrigin("http://localhost:5173"),
                "the ACAO header must echo the exact requesting origin, never '*'");
    }

    // ------------------------------------------------------------------
    // 7. Authorization header
    // ------------------------------------------------------------------

    @Test
    void authorizationAndContentTypeHeadersAreAllowed() {
        CorsConfiguration config = configFor("https://carbon.example.com");

        assertTrue(config.getAllowedHeaders().contains("Authorization"),
                "the Bearer header must survive the preflight");
        assertTrue(config.getAllowedHeaders().contains("Content-Type"),
                "JSON bodies must survive the preflight");
        assertTrue(config.getAllowedMethods().containsAll(
                        List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")),
                "the whole API verb set must survive the preflight");
    }

    // ------------------------------------------------------------------
    // 8. Multiple allowed origins
    // ------------------------------------------------------------------

    @Test
    void multipleConfiguredOriginsAreAllHonoured() {
        CorsConfiguration config = configFor(
                "https://carbon.example.com, https://staging.carbon.example.com ,https://eu.carbon.example.com");

        assertEquals(List.of("https://carbon.example.com",
                "https://staging.carbon.example.com",
                "https://eu.carbon.example.com"), config.getAllowedOrigins(),
                "whitespace around a configured origin must be trimmed, not break the list");
        assertTrue(allows(config, "https://carbon.example.com"));
        assertTrue(allows(config, "https://staging.carbon.example.com"));
        assertTrue(allows(config, "https://eu.carbon.example.com"));
    }

    // ------------------------------------------------------------------
    // Never "*" with credentials
    // ------------------------------------------------------------------

    @Test
    void wildcardOriginIsRefusedAtStartup() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.corsConfigurationSource("*"));
        assertTrue(failure.getMessage().contains("credentials"),
                "the failure must explain why the wildcard is refused");

        // Also refused when smuggled in beside a legitimate origin.
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.corsConfigurationSource("https://carbon.example.com,*"));
    }

    @Test
    void nullAndBlankValuesDegradeToAnEmptyAllowList() {
        assertTrue(configFor(null).getAllowedOrigins().isEmpty());
        assertTrue(configFor("   ,  ,").getAllowedOrigins().isEmpty());
    }
}
