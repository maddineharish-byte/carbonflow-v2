package com.carbonflow.config;

import com.carbonflow.model.User;
import com.carbonflow.repository.DataStore;
import com.carbonflow.security.Authorities;
import com.carbonflow.security.Permission;
import com.carbonflow.security.RolePermissions;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the bearer token into an authenticated principal carrying the
 * canonical role plus one authority per granted permission
 * ({@code PERMISSION_<code>}), which method security then enforces.
 *
 * <p>The tenant context is bound to this thread for the duration of the request
 * and always cleared in {@code finally}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider tokenProvider;
    private final DataStore dataStore;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, DataStore dataStore) {
        this.tokenProvider = tokenProvider;
        this.dataStore = dataStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {
            try {
                Claims claims = tokenProvider.parseToken(token);
                String userId = claims.getSubject();
                User user = dataStore.users.get(userId);

                if (user != null && user.isActive()) {
                    TenantContext context = new TenantContext(
                            user.getOrganizationId(),
                            user.getId(),
                            user.getEmail(),
                            user.getRole(),
                            user.getFacilityScopes()
                    );
                    TenantContext.set(context);

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            buildAuthorities(user)
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    log.debug("Rejected token for unknown or inactive user id {}", userId);
                }
            } catch (Exception e) {
                // Invalid, expired or tampered token: proceed unauthenticated.
                log.debug("Rejected bearer token: {}", e.getMessage());
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
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
    private List<GrantedAuthority> buildAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Authorities.ROLE_PREFIX + user.getRole().name()));
        for (Permission permission : RolePermissions.forRole(user.getRole())) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }
        return authorities;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // NOTE: query-string tokens leak into logs and history; accepted only for
        // legacy CSV/export links. Removal is scheduled with the auth hardening work.
        String tokenParam = request.getParameter("token");
        if (tokenParam != null && !tokenParam.isBlank()) {
            return tokenParam;
        }
        return null;
    }
}
