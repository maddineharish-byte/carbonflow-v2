package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenant user administration ({@code /users}, greenfield per ADR-014): list,
 * create, update, enable/disable — all scoped to the caller's organization, with
 * the lockout protections (no self-disable / self role change) and the
 * PLATFORM_ADMIN assignment ban.
 */
class UserAdminTest extends PostgresBackedIntegrationTest {

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    @Test
    void listsMembersOfTheOwnTenant() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = getJson("/api/v1/users", token);
        assertEquals(200, api.status());
        JsonNode members = api.body().get("data");
        assertTrue(members.isArray() && members.size() >= 3,
                "Acme seed holds at least admin, manager and auditor");

        boolean foundAdmin = false;
        for (JsonNode member : members) {
            assertTrue(member.has("id") && member.has("email") && member.has("role")
                    && member.has("active") && member.has("createdAt"));
            if ("admin@acmeglobal.com".equals(member.get("email").asText())) {
                foundAdmin = true;
                assertEquals(SeedIds.USER_ACME_ADMIN, member.get("id").asText());
                assertEquals("COMPANY_ADMIN", member.get("role").asText());
                assertTrue(member.get("active").asBoolean());
            }
        }
        assertTrue(foundAdmin, "seeded admin must appear in the listing");
    }

    @Test
    void createsAUserThatCanSignInImmediately() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String email = uniqueEmail();

        Api created = postJson("/api/v1/users", token,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Probe Person\",\"role\":\"REVIEWER\"}");
        assertEquals(201, created.status());
        assertEquals("User created successfully.", created.body().path("message").asText());
        assertEquals("REVIEWER", created.body().path("data").path("role").asText());
        assertTrue(created.body().path("data").path("active").asBoolean());
        String userId = created.body().path("data").path("id").asText();

        // The new identity authenticates and reads the tenant context.
        JsonNode session = login(email, SeedIds.DEMO_PASSWORD);
        assertEquals(userId, session.get("data").get("user").get("id").asText());
        assertEquals("REVIEWER", session.get("data").get("role").asText());

        // And it shows up in the listing.
        JsonNode members = getJson("/api/v1/users", token).body().get("data");
        boolean listed = false;
        for (JsonNode member : members) {
            if (email.equals(member.get("email").asText())) {
                listed = true;
            }
        }
        assertTrue(listed, "created user must appear in the listing");
    }

    @Test
    void refusesDuplicateEmails() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                + "\",\"fullName\":\"First\",\"role\":\"REVIEWER\"}";

        assertEquals(201, postJson("/api/v1/users", token, body).status());

        Api duplicate = postJson("/api/v1/users", token, body);
        assertEquals(409, duplicate.status());
        assertEquals("EMAIL_ALREADY_REGISTERED", duplicate.errorCode());
    }

    @Test
    void refusesPlatformAdminAndUnknownRoles() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api platform = postJson("/api/v1/users", token,
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Sneaky\",\"role\":\"PLATFORM_ADMIN\"}");
        assertEquals(400, platform.status());
        assertEquals("VALIDATION_ERROR", platform.errorCode());

        Api unknown = postJson("/api/v1/users", token,
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Sneaky\",\"role\":\"WIZARD\"}");
        assertEquals(400, unknown.status());
        assertEquals("VALIDATION_ERROR", unknown.errorCode());
    }

    @Test
    void administratorCannotDisableTheirOwnAccount() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = postJson("/api/v1/users/" + SeedIds.USER_ACME_ADMIN + "/disable", token, null);
        assertEquals(400, api.status());
        assertEquals("VALIDATION_ERROR", api.errorCode());
        assertEquals("You cannot disable your own account.", api.errorMessage());
    }

    @Test
    void disableAndEnableGateAuthentication() throws Exception {
        String adminToken = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String email = uniqueEmail();
        Api created = postJson("/api/v1/users", adminToken,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Toggle User\",\"role\":\"DATA_OWNER\"}");
        assertEquals(201, created.status());
        String userId = created.body().path("data").path("id").asText();

        Api disabled = postJson("/api/v1/users/" + userId + "/disable", adminToken, null);
        assertEquals(200, disabled.status());
        assertFalse(disabled.body().path("data").path("active").asBoolean());

        Api loginBlocked = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(401, loginBlocked.status());
        assertEquals("USER_DEACTIVATED", loginBlocked.errorCode());

        Api enabled = postJson("/api/v1/users/" + userId + "/enable", adminToken, null);
        assertEquals(200, enabled.status());
        assertTrue(enabled.body().path("data").path("active").asBoolean());
        // login() asserts a 200 envelope itself — re-enabled account can sign in again.
        assertEquals("DATA_OWNER", login(email, SeedIds.DEMO_PASSWORD)
                .get("data").get("role").asText());
    }

    @Test
    void roleUpdateChangesTheAssignment() throws Exception {
        String adminToken = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String email = uniqueEmail();
        Api created = postJson("/api/v1/users", adminToken,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Role Changer\",\"role\":\"REVIEWER\"}");
        assertEquals(201, created.status());
        String userId = created.body().path("data").path("id").asText();

        Api patched = patchJson("/api/v1/users/" + userId, adminToken,
                "{\"role\":\"DATA_OWNER\",\"fullName\":\"Role Changer II\"}");
        assertEquals(200, patched.status());
        assertEquals("DATA_OWNER", patched.body().path("data").path("role").asText());
        assertEquals("Role Changer II", patched.body().path("data").path("fullName").asText());

        // The session issued after the change reflects the new role.
        assertEquals("DATA_OWNER", login(email, SeedIds.DEMO_PASSWORD)
                .get("data").get("role").asText());
    }

    @Test
    void usersOfOtherTenantsAreInvisibleAndUntouchable() throws Exception {
        String apexToken = loginToken("admin@apexcorp.com", SeedIds.DEMO_PASSWORD);

        JsonNode members = getJson("/api/v1/users", apexToken).body().get("data");
        boolean containsForeign = false;
        boolean containsOwn = false;
        for (JsonNode member : members) {
            if ("admin@acmeglobal.com".equals(member.get("email").asText())) {
                containsForeign = true;
            }
            if ("admin@apexcorp.com".equals(member.get("email").asText())) {
                containsOwn = true;
            }
        }
        assertTrue(containsOwn, "apex admin sees its own tenant members");
        assertFalse(containsForeign, "cross-tenant members must not be listed");

        // Reaching for an Acme user id from the Apex tenant answers 404.
        Api patch = patchJson("/api/v1/users/" + SeedIds.USER_ACME_MANAGER, apexToken,
                "{\"fullName\":\"Should Not Apply\"}");
        assertEquals(404, patch.status());
        assertEquals("USER_NOT_FOUND", patch.errorCode());

        Api disable = postJson("/api/v1/users/" + SeedIds.USER_ACME_MANAGER + "/disable",
                apexToken, null);
        assertEquals(404, disable.status());
        assertEquals("USER_NOT_FOUND", disable.errorCode());
    }

    @Test
    void rolesWithoutUsersPermissionsAreDenied() throws Exception {
        String auditorToken = loginToken("auditor@ey-assurance.com", SeedIds.DEMO_PASSWORD);

        assertEquals(403, getJson("/api/v1/users", auditorToken).status());

        Api create = postJson("/api/v1/users", auditorToken,
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD
                        + "\",\"fullName\":\"Nope\",\"role\":\"REVIEWER\"}");
        assertEquals(403, create.status());
        assertEquals("FORBIDDEN", create.errorCode());
    }

    private Api patchJson(String uri, String bearerToken, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + bearerToken);
        var result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString()));
    }
}
