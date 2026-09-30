package com.carbonflow.config;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * Phase 10.4.1 finding 5 — end-to-end CORS behaviour through the real security
 * filter chain (not the {@link SecurityConfig#corsConfigurationSource(String)}
 * factory in isolation).
 *
 * <p>Runs under the {@code dev} profile, whose allow-list is exactly the two
 * localhost development origins from {@code application-dev.properties}. What is
 * asserted here is the browser-observable contract: the
 * {@code Access-Control-Allow-Origin} header is emitted <b>only</b> for a
 * configured origin, and credentials ride along with it.
 *
 * <p>{@link CorsConfigurationTest} covers the production side (an explicit
 * deployment allow-list, no implicit localhost, no {@code *}).
 */
@ActiveProfiles({"dbtest", "dev"})
class CorsOriginIntegrationTest extends PostgresBackedIntegrationTest {

    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final String LEGACY_NODE_ORIGIN = "http://localhost:3000";
    private static final String UNLISTED_ORIGIN = "http://evil.example";

    private String allowOriginOf(MvcResult result) {
        return result.getResponse().getHeader("Access-Control-Allow-Origin");
    }

    // ------------------------------------------------------------------
    // 4 + 6. Development origin, credentials, preflight
    // ------------------------------------------------------------------

    @Test
    void developmentPreflightFromTheViteDevServerIsAllowedWithCredentials() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/facilities")
                        .header("Origin", DEV_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(DEV_ORIGIN, allowOriginOf(result),
                "the preflight must echo the exact requesting origin");
        assertEquals("true", result.getResponse().getHeader("Access-Control-Allow-Credentials"),
                "credentialed requests must be permitted for a configured origin");
        String headers = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertNotNull(headers, "the preflight must answer Access-Control-Allow-Headers");
        org.junit.jupiter.api.Assertions.assertTrue(
                headers.toLowerCase(java.util.Locale.ROOT).contains("authorization"),
                "the Authorization header must be allowed through the preflight, got: " + headers);
    }

    @Test
    void developmentPreflightFromTheRetiredNodeOriginIsStillAllowed() throws Exception {
        // Local development is preserved, not removed globally: a developer can
        // still point a browser at a same-machine client on :3000.
        MvcResult result = mockMvc.perform(options("/api/v1/facilities")
                        .header("Origin", LEGACY_NODE_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(LEGACY_NODE_ORIGIN, allowOriginOf(result));
        assertEquals("true", result.getResponse().getHeader("Access-Control-Allow-Credentials"));
    }

    // ------------------------------------------------------------------
    // 2 + 5 + 7. Unlisted origins, and a real authenticated call
    // ------------------------------------------------------------------

    @Test
    void unlistedOriginPreflightReceivesNoAllowOriginHeader() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/facilities")
                        .header("Origin", UNLISTED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn();

        assertNull(allowOriginOf(result),
                "an unlisted origin must never receive Access-Control-Allow-Origin");
        assertNull(result.getResponse().getHeader("Access-Control-Allow-Credentials"),
                "an unlisted origin must never receive Access-Control-Allow-Credentials");
    }

    @Test
    void unlistedOriginIsRefusedEvenOnAnAuthenticatedRequest() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/v1/facilities")
                        .header("Origin", UNLISTED_ORIGIN)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertNull(allowOriginOf(result),
                "a valid token must not turn an unlisted origin into an allowed one");
    }

    @Test
    void authenticatedRequestFromAConfiguredOriginKeepsBearerAndCredentials() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/v1/facilities")
                        .header("Origin", DEV_ORIGIN)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(DEV_ORIGIN, allowOriginOf(result),
                "a configured origin must be echoed on the real request too");
        assertEquals("true", result.getResponse().getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void sameOriginRequestNeedsNoCorsHeaders() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertNull(allowOriginOf(result),
                "a request without an Origin header is not a CORS request and must not gain headers");
    }
}
