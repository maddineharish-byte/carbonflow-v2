package com.carbonflow.config;

import com.carbonflow.model.User;
import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.DataStore;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                // Invalid or expired token
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

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        String tokenParam = request.getParameter("token");
        if (tokenParam != null && !tokenParam.isBlank()) {
            return tokenParam;
        }
        return null;
    }
}
