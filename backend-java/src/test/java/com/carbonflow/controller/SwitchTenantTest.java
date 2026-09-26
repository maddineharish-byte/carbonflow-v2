package com.carbonflow.controller;

import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.IdentityRepository;
import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /auth/switch-tenant-or-role} — the Node contract: switching never
 * creates memberships, only already-assigned (organization, role) pairs may be
 * selected, PLATFORM_ADMIN is never switchable, and unknown organization vs.
 * unassigned role answer the deliberately indistinguishable
 * {@code SWITCH_NOT_AUTHORIZED}.
 */
class SwitchTenantTest extends PostgresBackedIntegrationTest {

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void auditorCanSwitchToTheirSecondOrganization() throws Exception {
        // Seeded auditor holds Acme AND Apex (ASSURANCE_PROVIDER in both).
        JsonNode session = login("auditor@ey-assurance.com", SeedIds.DEMO_PASSWORD);
        assertEquals(SeedIds.ORG_ACME, session.get("data").get("organization").get("id").asText(),
                "memberships are ordered by organization name; Acme sorts first");

        Api switched = postJson("/api/v1/auth/switch-tenant-or-role",
                session.get("data").get("accessToken").asText(),
                "{\"targetOrgId\":\"" + SeedIds.ORG_APEX + "\",\"targetRole\":\"ASSURANCE_PROVIDER\"}");
        assertEquals(200, switched.status());
        JsonNode data = switched.body().get("data");
        assertEquals(SeedIds.ORG_APEX, data.get("organization").get("id").asText());
        assertEquals("ASSURANCE_PROVIDER", data.get("role").asText());
        // Membership options keep the name-ordered listing (Acme first) — the
        // target tenant is simply among them, exactly like Node's
        // membershipOptions(principal).
        assertEquals(2, data.get("memberships").size(),
                "auditor holds exactly two memberships");
        boolean targetListed = false;
        for (JsonNode option : data.get("memberships")) {
            if (SeedIds.ORG_APEX.equals(option.path("organizationId").asText())) {
                targetListed = true;
            }
        }
        assertTrue(targetListed, "target organization must be among the switch options");
        assertTrue(data.get("permissions").size() > 0,
                "switch response must carry the target role's permission codes");

        String newAccess = data.get("accessToken").asText();
        String newRefresh = data.get("refreshToken").asText();
        assertNotEquals(session.get("data").get("accessToken").asText(), newAccess);

        // The switched token really operates the Apex tenant …
        Api current = getJson("/api/v1/organizations/current", newAccess);
        assertEquals(200, current.status());
        assertEquals(SeedIds.ORG_APEX, current.body().path("data").path("id").asText());

        // … and its refresh token rotates normally.
        Api refreshed = postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + newRefresh + "\"}");
        assertEquals(200, refreshed.status());
    }

    @Test
    void switchToPlatformAdminRoleIsRefused() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        Api api = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + SeedIds.ORG_ACME + "\",\"targetRole\":\"PLATFORM_ADMIN\"}");
        assertEquals(403, api.status());
        assertEquals("ROLE_NOT_SWITCHABLE", api.errorCode());
    }

    @Test
    void switchToUnassignedPairIsRefused() throws Exception {
        // Acme admin holds no membership in Apex.
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        Api api = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + SeedIds.ORG_APEX + "\",\"targetRole\":\"COMPANY_ADMIN\"}");
        assertEquals(403, api.status());
        assertEquals("SWITCH_NOT_AUTHORIZED", api.errorCode());
    }

    @Test
    void unknownAndUnassignedTargetsAreIndistinguishable() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String unknownOrg = UUID.randomUUID().toString();

        Api unassigned = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + SeedIds.ORG_APEX + "\",\"targetRole\":\"COMPANY_ADMIN\"}");
        Api unknown = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + unknownOrg + "\",\"targetRole\":\"COMPANY_ADMIN\"}");

        assertEquals(unassigned.status(), unknown.status());
        assertEquals(unassigned.errorCode(), unknown.errorCode());
        assertEquals(unassigned.errorMessage(), unknown.errorMessage());
    }

    @Test
    void switchValidatesItsFields() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api empty = postJson("/api/v1/auth/switch-tenant-or-role", token, "{}");
        assertEquals(400, empty.status());
        assertEquals("VALIDATION_ERROR", empty.errorCode());

        Api unknownRole = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + SeedIds.ORG_APEX + "\",\"targetRole\":\"NOT_A_ROLE\"}");
        assertEquals(400, unknownRole.status());
        assertEquals("VALIDATION_ERROR", unknownRole.errorCode());
    }

    @Test
    void deactivatedMembershipCannotBeSwitchedInto() throws Exception {
        String userId = UUID.randomUUID().toString();
        String email = "switch-" + userId.substring(0, 8) + "@example.test";
        identityRepository.insertUser(userId, email, passwordEncoder.encode(SeedIds.DEMO_PASSWORD),
                "Switch Probe");
        identityRepository.insertMembership(UUID.randomUUID().toString(),
                SeedIds.ORG_ACME, userId, Role.REVIEWER);

        String token = loginToken(email, SeedIds.DEMO_PASSWORD);
        jdbc.update("UPDATE organization_memberships SET is_active = false "
                + "WHERE user_id = ?", userId);

        // The per-request filter re-validates the token's tenant context first
        // (Node's authenticateTenant does the same) — a token whose membership
        // died is rejected as TENANT_ACCESS_DENIED before the switch handler
        // ever runs, so SWITCH_NOT_AUTHORIZED is unreachable for this path.
        Api api = postJson("/api/v1/auth/switch-tenant-or-role", token,
                "{\"targetOrgId\":\"" + SeedIds.ORG_ACME + "\",\"targetRole\":\"REVIEWER\"}");
        assertEquals(403, api.status());
        assertEquals("TENANT_ACCESS_DENIED", api.errorCode());
        assertEquals("User does not belong to this organization.", api.errorMessage());
    }

    @Test
    void switchingRequiresAnAuthenticatedCaller() throws Exception {
        Api api = postJson("/api/v1/auth/switch-tenant-or-role", null,
                "{\"targetOrgId\":\"" + SeedIds.ORG_APEX + "\",\"targetRole\":\"ASSURANCE_PROVIDER\"}");
        assertEquals(401, api.status());
        assertEquals("UNAUTHORIZED", api.errorCode());
    }
}
