package com.carbonflow.config;

import com.carbonflow.model.Membership;
import com.carbonflow.model.User;
import com.carbonflow.repository.IdentityRepository;
import com.carbonflow.security.Authorities;
import com.carbonflow.security.Permission;
import com.carbonflow.security.RolePermissions;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Validates the bearer token (from the {@code Authorization} header only —
 * query-string tokens were removed in Phase 9) and — like the Node
 * reference backend's production path ({@code authenticateTenant}) —
 * <strong>re-validates the identity against PostgreSQL on every request</strong>:
 * the user must exist and be active, and hold an active membership in the
 * organization named by the token. Failure answers immediately with Node's
 * exact contract codes instead of degrading to a generic 401:
 *
 * <ul>
 *   <li>401 {@code INVALID_TOKEN} — expired/tampered token</li>
 *   <li>401 {@code USER_DEACTIVATED} — unknown or inactive user</li>
 *   <li>403 {@code TENANT_ACCESS_DENIED} — no active membership in that
 *       organization</li>
 *   <li>503 {@code AUTH_PERSISTENCE_UNAVAILABLE} — database unreachable</li>
 * </ul>
 *
 * <p>Presenting no token at all lets the chain continue; secured endpoints are
 * then answered by the entry point with 401 {@code UNAUTHORIZED}.
 *
 * <p>Organization lifecycle status (V8) is intentionally NOT checked here —
 * it gates token <em>issuance</em> (login/refresh); an already-issued access
 * token lives at most 15 minutes after a suspension (ADR-014).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider tokenProvider;
    private final IdentityRepository identityRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   IdentityRepository identityRepository,
                                   ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.identityRepository = identityRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {
            Claims claims;
            try {
                claims = tokenProvider.parseToken(token);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected bearer token: {}", e.getMessage());
                writeEnvelope(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                        "Access token expired or signature invalid.");
                return;
            }

            String userId = claims.getSubject();
            String organizationId = claims.get("orgId", String.class);

            User user;
            Membership membership;
            try {
                user = identityRepository.findUserById(userId).orElse(null);
                membership = (user == null || organizationId == null)
                        ? null
                        : identityRepository.findMembership(userId, organizationId).orElse(null);
            } catch (DataAccessException e) {
                log.error("Identity lookup failed while authenticating request", e);
                writeEnvelope(response, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PERSISTENCE_UNAVAILABLE",
                        "Authentication persistence is temporarily unavailable.");
                return;
            }

            if (user == null || !user.isActive()) {
                log.debug("Rejected token for unknown or inactive user id {}", userId);
                writeEnvelope(response, HttpStatus.UNAUTHORIZED, "USER_DEACTIVATED",
                        "User account is inactive or deleted.");
                return;
            }
            if (membership == null || !membership.active()) {
                writeEnvelope(response, HttpStatus.FORBIDDEN, "TENANT_ACCESS_DENIED",
                        "User does not belong to this organization.");
                return;
            }

            TenantContext.set(new TenantContext(
                    membership.organization().getId(),
                    user.getId(),
                    user.getEmail(),
                    membership.role()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user.getId(), null, buildAuthorities(membership.role()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Role authority plus one authority per permission of the assigned role, so
     * {@code @PreAuthorize("hasAuthority('PERMISSION_...')")} can enforce the
     * RBAC matrix independently of the frontend.
     */
    private List<GrantedAuthority> buildAuthorities(com.carbonflow.model.enums.Role role) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Authorities.ROLE_PREFIX + role.name()));
        for (Permission permission : RolePermissions.forRole(role)) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }
        return authorities;
    }

    /** Envelope shape shared with the security chain's 401/403 handlers. */
    private void writeEnvelope(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("success", false, "error", Map.of("code", code, "message", message)));
    }

    /**
     * Phase 9 hardening: the access token is accepted <b>only</b> through the
     * {@code Authorization: Bearer} header. The Node-parity {@code ?token=}
     * query parameter was removed: query strings leak credentials into browser
     * history, proxy/access logs, {@code Referer} headers and server-side
     * request logs, which no frontend feature requires (the React client has
     * always used the authenticated blob/download flow).
     */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
