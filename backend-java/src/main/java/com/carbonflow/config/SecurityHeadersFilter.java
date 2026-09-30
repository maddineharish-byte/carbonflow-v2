package com.carbonflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ensures every HTTP response includes security hardening headers.
 * <p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing</li>
 *   <li>{@code X-Frame-Options: SAMEORIGIN} — prevents framing on other origins</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — limits referrer leakage</li>
 *   <li>{@code X-XSS-Protection: 1; mode=block} — legacy IE XSS filter (no-op in modern browsers but harmless)</li>
 * </ul>
 * <p>
 * HSTS is NOT set here because the application runs HTTP in development; production
 * deployments behind HTTPS should enable HSTS separately (reverse proxy or external
 * TLS termination).
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        filterChain.doFilter(request, response);
    }
}