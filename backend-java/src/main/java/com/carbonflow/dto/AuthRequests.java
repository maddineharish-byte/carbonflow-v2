package com.carbonflow.dto;

import com.carbonflow.model.Organization;
import com.carbonflow.model.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request/response payloads for the authentication endpoints — ported to the
 * exact contract of the Node reference backend ({@code server/routes.ts},
 * ADR-010):
 *
 * <ul>
 *   <li>{@code user} in responses is the slim public view
 *       {@code {id, email, fullName}} (Node {@code getPublicUser()}); role,
 *       organization, permissions and memberships are siblings of {@code user},
 *       not fields inside it.</li>
 *   <li>Login/switch responses carry both tokens plus the membership options;
 *       {@code /auth/me} returns the same context without tokens.</li>
 * </ul>
 */
public class AuthRequests {

    /** POST /auth/login: {@code {email, password, organizationId?}} */
    public static class LoginRequest {
        @NotBlank(message = "email is required")
        private String email;
        @NotBlank(message = "password is required")
        private String password;
        /** Optional membership selector; 403 when not assigned to the user. */
        private String organizationId;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    }

    /** The public user view — identical to Node's {@code getPublicUser()}. */
    public record PublicUser(String id, String email, String fullName) {
    }

    /** One selectable organization/role pair (Node {@code MembershipOption}). */
    public record MembershipOption(String organizationId, String organizationName, Role role) {
    }

    /** Login / switch-tenant-or-role response (Node contract, both tokens). */
    public static class AuthSession {
        private final PublicUser user;
        private final Organization organization;
        private final Role role;
        private final List<String> permissions;
        private final List<MembershipOption> memberships;
        private final String accessToken;
        private final String refreshToken;

        public AuthSession(PublicUser user, Organization organization, Role role,
                           List<String> permissions, List<MembershipOption> memberships,
                           String accessToken, String refreshToken) {
            this.user = user;
            this.organization = organization;
            this.role = role;
            this.permissions = permissions;
            this.memberships = memberships;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public PublicUser getUser() { return user; }
        public Organization getOrganization() { return organization; }
        public Role getRole() { return role; }
        public List<String> getPermissions() { return permissions; }
        public List<MembershipOption> getMemberships() { return memberships; }
        public String getAccessToken() { return accessToken; }
        public String getRefreshToken() { return refreshToken; }
    }

    /** GET /auth/me — the session context without tokens. */
    public static class Profile {
        private final PublicUser user;
        private final Organization organization;
        private final Role role;
        private final List<String> permissions;
        private final List<MembershipOption> memberships;

        public Profile(PublicUser user, Organization organization, Role role,
                       List<String> permissions, List<MembershipOption> memberships) {
            this.user = user;
            this.organization = organization;
            this.role = role;
            this.permissions = permissions;
            this.memberships = memberships;
        }

        public PublicUser getUser() { return user; }
        public Organization getOrganization() { return organization; }
        public Role getRole() { return role; }
        public List<String> getPermissions() { return permissions; }
        public List<MembershipOption> getMemberships() { return memberships; }
    }

    /** POST /auth/refresh success body: a fresh token pair. */
    public static class RefreshResponse {
        private final String accessToken;
        private final String refreshToken;

        public RefreshResponse(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public String getAccessToken() { return accessToken; }
        public String getRefreshToken() { return refreshToken; }
    }

    /**
     * POST /auth/switch-tenant-or-role body. No validation annotations on
     * purpose: the service produces the exact Node error codes/messages
     * (VALIDATION_ERROR vs ROLE_NOT_SWITCHABLE vs SWITCH_NOT_AUTHORIZED).
     */
    public static class SwitchTenantRequest {
        private String targetOrgId;
        private String targetRole;

        public String getTargetOrgId() { return targetOrgId; }
        public void setTargetOrgId(String targetOrgId) { this.targetOrgId = targetOrgId; }

        public String getTargetRole() { return targetRole; }
        public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    }

    /**
     * POST /auth/register — public signup creating an organization in
     * {@code PENDING_ACTIVATION} (greenfield endpoint documented in API.md;
     * design recorded in ADR-014).
     */
    public static class RegisterRequest {
        @NotBlank(message = "organizationName is required")
        private String organizationName;
        /** Defaults to {@code US} when blank. */
        private String country;
        private String industry;
        private String taxId;
        @NotBlank(message = "fullName is required")
        private String fullName;
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        private String email;
        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        private String password;

        public String getOrganizationName() { return organizationName; }
        public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }

        public String getIndustry() { return industry; }
        public void setIndustry(String industry) { this.industry = industry; }

        public String getTaxId() { return taxId; }
        public void setTaxId(String taxId) { this.taxId = taxId; }

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * Registration result: the created identity — no tokens are issued until a
     * platform administrator activates the organization.
     */
    public record RegisterResponse(PublicUser user, Organization organization) {
    }

    /** POST /auth/logout success body (Node contract). */
    public record LogoutResponse(boolean revoked) {
    }
}
