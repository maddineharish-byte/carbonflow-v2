package com.carbonflow.service;

import com.carbonflow.config.JwtTokenProvider;
import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.AuthRequests.AuthSession;
import com.carbonflow.dto.AuthRequests.LoginRequest;
import com.carbonflow.dto.AuthRequests.MembershipOption;
import com.carbonflow.dto.AuthRequests.Profile;
import com.carbonflow.dto.AuthRequests.PublicUser;
import com.carbonflow.dto.AuthRequests.RefreshResponse;
import com.carbonflow.dto.AuthRequests.RegisterRequest;
import com.carbonflow.dto.AuthRequests.RegisterResponse;
import com.carbonflow.dto.AuthRequests.SwitchTenantRequest;
import com.carbonflow.model.Membership;
import com.carbonflow.model.Organization;
import com.carbonflow.model.User;
import com.carbonflow.model.enums.OrganizationStatus;
import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.IdentityRepository;
import com.carbonflow.repository.OrganizationRepository;
import com.carbonflow.repository.RefreshTokenRepository;
import com.carbonflow.repository.RefreshTokenRepository.LockedToken;
import com.carbonflow.security.Permission;
import com.carbonflow.security.RolePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authentication and tenant-context flows — the Java port of
 * {@code server/auth.ts} + the auth handlers in {@code server/routes.ts},
 * against the PostgreSQL persistence of {@code server/postgres-refresh-repository.ts}.
 *
 * <p>Contract parity (ADR-010): same error codes, same status codes, same
 * token lifetimes (15-minute access JWT, 7-day refresh token), refresh tokens
 * stored only as HMAC-SHA256 hashes. Documented deviations (ADR-014):
 *
 * <ul>
 *   <li><strong>Replay of a rotated refresh token revokes the entire family</strong>
 *       (Node reports {@code REFRESH_TOKEN_REVOKED} but leaves the replacement
 *       usable — reuse detection without revocation is a known gap).</li>
 *   <li><strong>The organization must be ACTIVE</strong> (login/refresh) —
 *       new lifecycle states from V8, which Node does not have.</li>
 *   <li>Membership options exclude {@code PLATFORM_ADMIN} (Node only excludes
 *       them on its in-memory dev path).</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Valid BCrypt hash of a random throwaway string. When an account does not
     * exist, login still performs one full BCrypt verification against this
     * value so that response timing cannot distinguish an unknown account from
     * a wrong password (user-enumeration hardening).
     */
    static final String TIMING_EQUALIZER_HASH =
            "$2b$10$9zl/RKI67B2GfnKZhi2nzu9keezzNYt/acc46V5nG0hb5SGklfToa";

    /** Node accepts only 64 hex characters ({@code isValidRefreshTokenFormat}). */
    private static final Pattern REFRESH_TOKEN_FORMAT = Pattern.compile("^[a-f0-9]{64}$", Pattern.CASE_INSENSITIVE);

    /** Refresh lifetime: 7 days, identical to {@code REFRESH_TOKEN_TTL_MS}. */
    private static final long REFRESH_TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepository identityRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;
    private final byte[] refreshSecret;

    public AuthService(IdentityRepository identityRepository,
                       OrganizationRepository organizationRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       LoginThrottle loginThrottle,
                       @Value("${carbonflow.auth.refresh-secret:}") String refreshSecret) {
        this.identityRepository = identityRepository;
        this.organizationRepository = organizationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.loginThrottle = loginThrottle;
        // Fail closed, mirroring JwtTokenProvider: no committed default value.
        if (refreshSecret == null || refreshSecret.isBlank()) {
            throw new IllegalStateException(
                    "carbonflow.auth.refresh-secret is not configured. Export CARBONFLOW_REFRESH_TOKEN_SECRET "
                            + "(at least 32 random bytes) before starting the application.");
        }
        byte[] keyBytes = refreshSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "carbonflow.auth.refresh-secret must be at least 256 bits (32 bytes); got "
                            + keyBytes.length + " bytes.");
        }
        this.refreshSecret = keyBytes;
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    /**
     * Node parity: password is verified BEFORE the active flag, and the
     * optional {@code organizationId} must be one of the user's active
     * memberships (403 {@code NO_ORGANIZATION_ACCESS} otherwise). The
     * organization itself must also be ACTIVE (V8 lifecycle).
     */
    @Transactional
    public AuthSession login(LoginRequest request) {
        try {
            return doLogin(request);
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    private AuthSession doLogin(LoginRequest request) {
        // Phase 9: refuse a locked-out account before touching the password
        // store, so an online attack cannot keep guessing while locked.
        String throttleKey = loginThrottle.keyFor(request.getEmail());
        loginThrottle.assertNotLocked(throttleKey);

        IdentityRepository.Principal principal =
                identityRepository.loadPrincipalByEmail(request.getEmail()).orElse(null);

        boolean matches;
        if (principal == null) {
            // Timing equalization: always pay for one BCrypt verification.
            passwordEncoder.matches(request.getPassword(), TIMING_EQUALIZER_HASH);
            matches = false;
        } else {
            matches = passwordEncoder.matches(request.getPassword(), principal.user().getPasswordHash());
        }
        if (!matches) {
            // Count the miss (unknown account and wrong password alike — the
            // response is identical, so the counter leaks nothing).
            loginThrottle.recordFailure(throttleKey);
            throw new AuthException("INVALID_CREDENTIALS", "Invalid email or password.", HttpStatus.UNAUTHORIZED);
        }
        // Correct credentials: the streak is irrelevant from here on.
        loginThrottle.recordSuccess(throttleKey);

        User user = principal.user();
        if (!user.isActive()) {
            throw new AuthException("USER_DEACTIVATED", "The user account is inactive or deleted.", HttpStatus.UNAUTHORIZED);
        }

        List<Membership> active = principal.memberships().stream().filter(Membership::active).toList();
        if (active.isEmpty()) {
            throw new AuthException("NO_ORGANIZATION_ACCESS",
                    "User does not belong to any active organization.", HttpStatus.FORBIDDEN);
        }

        Membership selected;
        String requestedOrgId = request.getOrganizationId();
        if (requestedOrgId != null && !requestedOrgId.isBlank()) {
            selected = active.stream()
                    .filter(m -> requestedOrgId.equals(m.organization().getId()))
                    .findFirst()
                    .orElseThrow(() -> new AuthException("NO_ORGANIZATION_ACCESS",
                            "The requested organization is not assigned to this user.", HttpStatus.FORBIDDEN));
        } else {
            // Memberships are loaded ordered by organization name — deterministic.
            selected = active.get(0);
        }
        requireActiveOrganization(selected.organization());

        TokenPair tokens = issueTokens(user, selected);
        return new AuthSession(
                new PublicUser(user.getId(), user.getEmail(), user.getFullName()),
                selected.organization(),
                selected.role(),
                permissionCodes(selected.role()),
                membershipOptions(active),
                tokens.accessToken(),
                tokens.refreshToken());
    }

    // ------------------------------------------------------------------
    // Refresh rotation
    // ------------------------------------------------------------------

    /**
     * Rotation exactly as {@code rotateRefreshToken} in the Node backend:
     * {@code SELECT ... FOR UPDATE} inside this transaction, then either a
     * rejection that <em>persists</em> the revocation, or an atomic
     * insert-replacement + mark-revoked pair issuing a fresh pair.
     *
     * <p>{@code noRollbackFor = AuthException.class} is essential: rejection
     * paths (expiry, deactivated user, revoked/replaced replay) first write the
     * revocation and then throw — like Node's autocommit-per-statement
     * persistence, the revocation must survive the error response. Genuine
     * persistence failures ({@link AuthPersistenceException}) still roll back.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public RefreshResponse refresh(String rawRefreshToken) {
        try {
            String hash = hashRefreshToken(requireRefreshToken(rawRefreshToken));
            LockedToken token = refreshTokenRepository.lockByTokenHash(hash)
                    .orElseThrow(() -> new AuthException("INVALID_REFRESH_TOKEN",
                            "The refresh token is invalid.", HttpStatus.UNAUTHORIZED));

            Instant now = Instant.now();
            if (token.revoked() || token.replacedByTokenId() != null) {
                // A consumed token can only reappear through theft or a buggy
                // client replay: kill the whole family so the stolen and the
                // legit replacement both die (ADR-014 security hardening over Node).
                refreshTokenRepository.revokeFamily(token.familyId(), now);
                throw new AuthException("REFRESH_TOKEN_REVOKED",
                        "The refresh token has already been revoked.", HttpStatus.UNAUTHORIZED);
            }
            if (token.expiresAt().isBefore(now)) {
                refreshTokenRepository.revokeToken(token.tokenId(), now);
                throw new AuthException("REFRESH_TOKEN_EXPIRED",
                        "The refresh token has expired.", HttpStatus.UNAUTHORIZED);
            }
            if (!token.user().isActive()) {
                refreshTokenRepository.revokeToken(token.tokenId(), now);
                throw new AuthException("USER_DEACTIVATED",
                        "The associated user is inactive or deleted.", HttpStatus.UNAUTHORIZED);
            }
            if (!token.membershipActive()) {
                refreshTokenRepository.revokeToken(token.tokenId(), now);
                throw new AuthException("REFRESH_MEMBERSHIP_INVALID",
                        "The associated organization membership is no longer active.", HttpStatus.UNAUTHORIZED);
            }
            if (!token.organization().getStatus().allowsAuthentication()) {
                // V8 lifecycle: a suspended/rejected tenant dies at the next refresh.
                refreshTokenRepository.revokeToken(token.tokenId(), now);
                throw new AuthException("REFRESH_MEMBERSHIP_INVALID",
                        "The associated organization membership is no longer active.", HttpStatus.UNAUTHORIZED);
            }

            String replacementRaw = randomRefreshToken();
            String replacementId = UUID.randomUUID().toString();
            refreshTokenRepository.insertToken(
                    replacementId,
                    token.user().getId(),
                    token.organization().getId(),
                    token.role(),
                    token.familyId(),
                    hashRefreshToken(replacementRaw),
                    now.plusMillis(REFRESH_TOKEN_TTL_MS));
            refreshTokenRepository.markRotated(token.tokenId(), replacementId, now);

            String accessToken = jwtTokenProvider.generateToken(
                    token.user().getId(), token.user().getEmail(), token.user().getFullName(),
                    token.organization().getId(), token.role());
            return new RefreshResponse(accessToken, replacementRaw);
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    // ------------------------------------------------------------------
    // Logout (family revocation)
    // ------------------------------------------------------------------

    @Transactional(noRollbackFor = AuthException.class)
    public void logout(String rawRefreshToken) {
        try {
            String hash = hashRefreshToken(requireRefreshToken(rawRefreshToken));
            LockedToken token = refreshTokenRepository.lockByTokenHash(hash)
                    .orElseThrow(() -> new AuthException("INVALID_REFRESH_TOKEN",
                            "The refresh token is invalid.", HttpStatus.UNAUTHORIZED));
            if (token.revoked()) {
                throw new AuthException("REFRESH_TOKEN_REVOKED",
                        "The refresh token has already been revoked.", HttpStatus.UNAUTHORIZED);
            }
            refreshTokenRepository.revokeFamily(token.familyId(), Instant.now());
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    // ------------------------------------------------------------------
    // Tenant / role switching
    // ------------------------------------------------------------------

    /**
     * Node parity: switching never creates or mutates memberships — the target
     * (organization, role) pair must already be an active membership, the error
     * for "org unknown" and "role not held" is the deliberately
     * indistinguishable {@code SWITCH_NOT_AUTHORIZED}, and PLATFORM_ADMIN is
     * never switchable.
     */
    @Transactional
    public AuthSession switchTenant(SwitchTenantRequest request, TenantContext context) {
        try {
            String targetOrgId = request == null ? null : request.getTargetOrgId();
            String targetRole = request == null ? null : request.getTargetRole();
            if (isBlank(targetOrgId) || isBlank(targetRole)) {
                throw new AuthException("VALIDATION_ERROR",
                        "targetOrgId and targetRole are required.", HttpStatus.BAD_REQUEST);
            }
            Role requested;
            try {
                requested = Role.valueOf(targetRole.trim());
            } catch (IllegalArgumentException e) {
                throw new AuthException("VALIDATION_ERROR",
                        "The requested role is not supported.", HttpStatus.BAD_REQUEST);
            }
            if (requested == Role.PLATFORM_ADMIN) {
                throw new AuthException("ROLE_NOT_SWITCHABLE",
                        "PLATFORM_ADMIN cannot be selected through tenant switching.", HttpStatus.FORBIDDEN);
            }

            IdentityRepository.Principal principal =
                    identityRepository.loadPrincipalByUserId(context.getUserId()).orElse(null);
            Membership selected = null;
            if (principal != null && principal.user().isActive()) {
                selected = principal.memberships().stream()
                        .filter(m -> m.active()
                                && targetOrgId.equals(m.organization().getId())
                                && m.role() == requested)
                        .findFirst()
                        .orElse(null);
            }
            if (selected == null) {
                throw new AuthException("SWITCH_NOT_AUTHORIZED",
                        "The requested organization and role are not authorized for this user.",
                        HttpStatus.FORBIDDEN);
            }
            requireActiveOrganization(selected.organization());

            List<Membership> active = principal.memberships().stream().filter(Membership::active).toList();
            TokenPair tokens = issueTokens(principal.user(), selected);
            return new AuthSession(
                    new PublicUser(principal.user().getId(), principal.user().getEmail(), principal.user().getFullName()),
                    selected.organization(),
                    selected.role(),
                    permissionCodes(selected.role()),
                    membershipOptions(active),
                    tokens.accessToken(),
                    tokens.refreshToken());
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    // ------------------------------------------------------------------
    // Current session
    // ------------------------------------------------------------------

    /**
     * Node production path: re-validates user, membership and tenant context on
     * every call (the per-request filter already did — this response must be
     * fresh regardless).
     */
    @Transactional(readOnly = true)
    public Profile me(TenantContext context) {
        try {
            IdentityRepository.Principal principal =
                    identityRepository.loadPrincipalByUserId(context.getUserId())
                            .orElseThrow(tenantInactive());
            if (!principal.user().isActive()) {
                throw tenantInactive().get();
            }
            Membership current = principal.memberships().stream()
                    .filter(m -> m.active() && context.getOrganizationId().equals(m.organization().getId()))
                    .findFirst()
                    .orElseThrow(tenantInactive());

            List<Membership> active = principal.memberships().stream().filter(Membership::active).toList();
            return new Profile(
                    new PublicUser(principal.user().getId(), principal.user().getEmail(), principal.user().getFullName()),
                    current.organization(),
                    current.role(),
                    permissionCodes(current.role()),
                    membershipOptions(active));
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    // ------------------------------------------------------------------
    // Public registration (greenfield, ADR-014)
    // ------------------------------------------------------------------

    /**
     * Creates organization ({@code PENDING_ACTIVATION}) + COMPANY_ADMIN user +
     * membership atomically. No tokens are issued: sign-in stays blocked until
     * a platform administrator activates the organization.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        try {
            String email = request.getEmail().trim();
            if (identityRepository.emailExists(email)) {
                throw new AuthException("EMAIL_ALREADY_REGISTERED",
                        "An account with this email already exists.", HttpStatus.CONFLICT);
            }
            String organizationId = UUID.randomUUID().toString();
            int created = organizationRepository.insert(
                    organizationId,
                    request.getOrganizationName().trim(),
                    isBlank(request.getCountry()) ? "US" : request.getCountry().trim(),
                    blankToNull(request.getIndustry()),
                    blankToNull(request.getTaxId()),
                    OrganizationStatus.PENDING_ACTIVATION);
            if (created == 0) {
                throw new IllegalStateException("UUID collision while creating organization " + organizationId);
            }
            String userId = UUID.randomUUID().toString();
            String passwordHash = passwordEncoder.encode(request.getPassword());
            if (identityRepository.insertUser(userId, email, passwordHash, request.getFullName().trim()) == 0) {
                // Race against a concurrent registration with the same email:
                // AuthException rolls the pending organization back.
                throw new AuthException("EMAIL_ALREADY_REGISTERED",
                        "An account with this email already exists.", HttpStatus.CONFLICT);
            }
            identityRepository.insertMembership(UUID.randomUUID().toString(), organizationId, userId, Role.COMPANY_ADMIN);

            Organization organization = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new IllegalStateException("Organization vanished mid-registration"));
            return new RegisterResponse(new PublicUser(userId, email, request.getFullName().trim()), organization);
        } catch (DataAccessException e) {
            throw persistence(e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireActiveOrganization(Organization organization) {
        if (organization.getStatus().allowsAuthentication()) {
            return;
        }
        String message = switch (organization.getStatus()) {
            case PENDING_ACTIVATION -> "The organization is pending platform approval.";
            case REJECTED -> "The organization registration was not approved.";
            case SUSPENDED -> "The organization is suspended.";
            case ACTIVE -> "The organization is active.";
        };
        throw new AuthException("ORGANIZATION_NOT_ACTIVE", message, HttpStatus.FORBIDDEN);
    }

    /** Issues the refresh row first, then signs the access JWT (Node order). */
    private TokenPair issueTokens(User user, Membership selected) {
        String familyId = UUID.randomUUID().toString();
        String rawRefreshToken = randomRefreshToken();
        refreshTokenRepository.insertToken(
                UUID.randomUUID().toString(),
                user.getId(),
                selected.organization().getId(),
                selected.role(),
                familyId,
                hashRefreshToken(rawRefreshToken),
                Instant.now().plusMillis(REFRESH_TOKEN_TTL_MS));
        String accessToken = jwtTokenProvider.generateToken(
                user.getId(), user.getEmail(), user.getFullName(),
                selected.organization().getId(), selected.role());
        return new TokenPair(accessToken, rawRefreshToken);
    }

    /** Active memberships offered for switching — PLATFORM_ADMIN excluded. */
    private static List<MembershipOption> membershipOptions(List<Membership> activeMemberships) {
        return activeMemberships.stream()
                .filter(m -> m.role() != Role.PLATFORM_ADMIN)
                .map(m -> new MembershipOption(
                        m.organization().getId(), m.organization().getName(), m.role()))
                .toList();
    }

    /** Canonical permission codes of the role (sorted for deterministic output). */
    static List<String> permissionCodes(Role role) {
        return RolePermissions.forRole(role).stream()
                .map(Permission::code)
                .sorted()
                .toList();
    }

    private String requireRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || !REFRESH_TOKEN_FORMAT.matcher(rawRefreshToken).matches()) {
            throw new AuthException("REFRESH_TOKEN_REQUIRED",
                    "A valid refresh token is required.", HttpStatus.BAD_REQUEST);
        }
        return rawRefreshToken;
    }

    /** Keyed hash of the opaque refresh token — the only form stored (ADR-010). */
    String hashRefreshToken(String rawRefreshToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(refreshSecret, "HmacSHA256"));
            return toHex(mac.doFinal(rawRefreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** 32 random bytes as 64 lowercase hex characters (Node randomBytes(32)). */
    private static String randomRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static java.util.function.Supplier<AuthException> tenantInactive() {
        return () -> new AuthException("TENANT_ACCESS_DENIED",
                "The authenticated tenant context is no longer active.", HttpStatus.FORBIDDEN);
    }

    private static AuthPersistenceException persistence(DataAccessException e) {
        log.error("Authentication persistence failure", e);
        return new AuthPersistenceException("Authentication persistence is temporarily unavailable.", e);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }
}
