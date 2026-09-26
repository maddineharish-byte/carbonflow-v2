package com.carbonflow.service;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.UserRequests.CreateUserRequest;
import com.carbonflow.dto.UserRequests.UpdateUserRequest;
import com.carbonflow.dto.UserRequests.UserView;
import com.carbonflow.model.Membership;
import com.carbonflow.model.User;
import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.IdentityRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tenant user administration ({@code /users}, greenfield — ADR-014).
 *
 * <p>All operations are scoped to the caller's organization through the
 * authenticated tenant context; users outside the tenant answer
 * {@code USER_NOT_FOUND} (404) so other tenants' identities are not
 * probeable. Guardrails:
 *
 * <ul>
 *   <li>{@code PLATFORM_ADMIN} cannot be assigned by tenant administrators
 *       (RBAC.md: platform administration has no tenant-side assignment path).</li>
 *   <li>Administrators cannot disable their own account or change their own
 *       role (lockout protection).</li>
 *   <li>{@code users.disable} flips the global {@code users.is_active} flag —
 *       a disabled account cannot authenticate anywhere (per-organization
 *       membership removal needs a dedicated endpoint, deferred).</li>
 * </ul>
 */
@Service
public class UsersService {

    private final IdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;

    public UsersService(IdentityRepository identityRepository, PasswordEncoder passwordEncoder) {
        this.identityRepository = identityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserView> list(TenantContext context) {
        try {
            return identityRepository.listMembers(context.getOrganizationId()).stream()
                    .map(m -> new UserView(m.id(), m.email(), m.fullName(), m.role(), m.active(), m.createdAt()))
                    .toList();
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("User persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public UserView create(TenantContext context, CreateUserRequest request) {
        try {
            Role role = parseRole(request.getRole());
            requireTenantAssignable(role);
            String email = request.getEmail().trim();
            if (identityRepository.emailExists(email)) {
                throw new AuthException("EMAIL_ALREADY_REGISTERED",
                        "An account with this email already exists.", HttpStatus.CONFLICT);
            }
            String userId = UUID.randomUUID().toString();
            if (identityRepository.insertUser(userId, email,
                    passwordEncoder.encode(request.getPassword()), request.getFullName().trim()) == 0) {
                throw new AuthException("EMAIL_ALREADY_REGISTERED",
                        "An account with this email already exists.", HttpStatus.CONFLICT);
            }
            identityRepository.insertMembership(UUID.randomUUID().toString(),
                    context.getOrganizationId(), userId, role);
            return viewOf(userId, context.getOrganizationId(), role);
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("User persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public UserView update(TenantContext context, String userId, UpdateUserRequest request) {
        try {
            Membership target = requireMember(context, userId);
            Role newRole = null;
            if (request != null && !isBlank(request.getRole())) {
                newRole = parseRole(request.getRole());
                requireTenantAssignable(newRole);
                if (userId.equals(context.getUserId()) && newRole != target.role()) {
                    throw new AuthException("VALIDATION_ERROR",
                            "You cannot change your own role.", HttpStatus.BAD_REQUEST);
                }
            }
            if (request != null && !isBlank(request.getFullName())) {
                identityRepository.updateFullName(userId, request.getFullName().trim());
            }
            if (newRole != null) {
                identityRepository.updateMembershipRole(context.getOrganizationId(), userId, newRole);
            }
            return viewOf(userId, context.getOrganizationId(), newRole != null ? newRole : target.role());
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("User persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public UserView disable(TenantContext context, String userId) {
        try {
            requireMember(context, userId);
            if (userId.equals(context.getUserId())) {
                throw new AuthException("VALIDATION_ERROR",
                        "You cannot disable your own account.", HttpStatus.BAD_REQUEST);
            }
            identityRepository.setUserActive(userId, false);
            Membership membership = requireMember(context, userId);
            return viewOf(userId, context.getOrganizationId(), membership.role());
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("User persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public UserView enable(TenantContext context, String userId) {
        try {
            Membership membership = requireMember(context, userId);
            identityRepository.setUserActive(userId, true);
            return viewOf(userId, context.getOrganizationId(), membership.role());
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("User persistence is temporarily unavailable.", e);
        }
    }

    // ------------------------------------------------------------------

    private Membership requireMember(TenantContext context, String userId) {
        return identityRepository.findMembership(userId, context.getOrganizationId())
                .filter(Membership::active)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND",
                        "User does not exist or access denied.", HttpStatus.NOT_FOUND));
    }

    private UserView viewOf(String userId, String organizationId, Role role) {
        User user = identityRepository.findUserById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND",
                        "User does not exist or access denied.", HttpStatus.NOT_FOUND));
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), role,
                user.isActive(), user.getCreatedAt());
    }

    private static Role parseRole(String rawRole) {
        try {
            return Role.valueOf(rawRole.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthException("VALIDATION_ERROR",
                    "The requested role is not supported.", HttpStatus.BAD_REQUEST);
        }
    }

    private static void requireTenantAssignable(Role role) {
        if (role == Role.PLATFORM_ADMIN) {
            throw new AuthException("VALIDATION_ERROR",
                    "PLATFORM_ADMIN cannot be assigned through tenant administration.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
