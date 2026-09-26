package com.carbonflow.security;

import com.carbonflow.model.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against RBAC drift: the Java matrix must stay set-for-set identical to
 * the exported matrix of the Node reference backend ({@code server/rbac.ts}),
 * and the role/permission vocabulary must match {@code server/types.ts} and
 * {@code db/migration/V2__seed_reference_data.sql}.
 */
class RolePermissionsParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> CANONICAL_ROLES = Set.of(
            "COMPANY_ADMIN", "SUSTAINABILITY_MANAGER", "CARBON_ACCOUNTANT", "DATA_OWNER",
            "FACILITY_MANAGER", "REVIEWER", "MANAGEMENT", "ASSURANCE_PROVIDER", "PLATFORM_ADMIN");

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> expectedMatrix() throws Exception {
        try (InputStream in = RolePermissionsParityTest.class.getResourceAsStream("/expected-role-permissions.json")) {
            assertNotNull(in, "expected-role-permissions.json must be on the test classpath");
            return MAPPER.readValue(in, Map.class);
        }
    }

    @Test
    void roleEnumIsTheCanonicalNineRoles() {
        Set<String> actual = Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(CANONICAL_ROLES, actual);
        // docs/RBAC.md: "there is strictly no SUPER_ADMIN role"
        assertFalse(actual.contains("SUPER_ADMIN"));
        assertFalse(actual.contains("AUDITOR"));
        assertFalse(actual.contains("VIEWER"));
        assertFalse(actual.contains("FACILITY_OFFICER"));
    }

    @Test
    void permissionEnumHas44UniqueCanonicalCodes() {
        assertEquals(44, Permission.values().length);
        Set<String> codes = new HashSet<>();
        for (Permission permission : Permission.values()) {
            assertTrue(codes.add(permission.code()), "duplicate permission code: " + permission.code());
        }
        assertEquals(44, codes.size());
    }

    @Test
    void javaMatrixMatchesNodeReferenceExactly() throws Exception {
        Map<String, List<String>> expected = expectedMatrix();
        assertEquals(9, expected.size(), "expected matrix must contain 9 roles");

        for (Role role : Role.values()) {
            List<String> expectedCodes = expected.get(role.name());
            assertNotNull(expectedCodes, "role missing from expected matrix: " + role);
            Set<String> actualCodes = RolePermissions.forRole(role).stream()
                    .map(Permission::code)
                    .collect(Collectors.toSet());
            assertEquals(new HashSet<>(expectedCodes), actualCodes,
                    "RBAC drift detected for role " + role.name());
        }
    }

    @Test
    void matrixGrantsAtLeastOnePermissionToEveryRole() {
        for (Role role : Role.values()) {
            assertFalse(RolePermissions.forRole(role).isEmpty(), "role has no permissions: " + role);
        }
    }

    @Test
    void everyPermissionIsGrantedToAtLeastOneRole() {
        Set<Permission> union = EnumSet.noneOf(Permission.class);
        RolePermissions.all().values().forEach(union::addAll);
        assertEquals(44, union.size(), "some permission is granted to no role");
    }

    @Test
    void spotChecksMatchTheDocumentedMatrix() {
        // COMPANY_ADMIN is the most privileged tenant role but does not review its own audit.
        assertTrue(RolePermissions.has(Role.COMPANY_ADMIN, Permission.AUDITS_APPROVE));
        assertTrue(RolePermissions.has(Role.COMPANY_ADMIN, Permission.USERS_DISABLE));
        assertFalse(RolePermissions.has(Role.COMPANY_ADMIN, Permission.AUDITS_REVIEW));
        assertFalse(RolePermissions.has(Role.COMPANY_ADMIN, Permission.PLATFORM_TENANTS_MANAGE));

        // MANAGEMENT is strictly read-only.
        assertFalse(RolePermissions.has(Role.MANAGEMENT, Permission.AUDITS_APPROVE));
        assertFalse(RolePermissions.has(Role.MANAGEMENT, Permission.FACILITIES_CREATE));
        assertTrue(RolePermissions.has(Role.MANAGEMENT, Permission.REPORTS_READ));

        // PLATFORM_ADMIN operates platform-wide, not on tenant organization data.
        assertEquals(5, RolePermissions.forRole(Role.PLATFORM_ADMIN).size());
        assertFalse(RolePermissions.has(Role.PLATFORM_ADMIN, Permission.ORGANIZATION_READ));
        assertTrue(RolePermissions.has(Role.PLATFORM_ADMIN, Permission.PLATFORM_TENANTS_MANAGE));

        // Legacy prototype roles are gone; authority strings use the shared prefix.
        assertEquals("PERMISSION_facilities.read", Permission.FACILITIES_READ.authority());
    }
}
