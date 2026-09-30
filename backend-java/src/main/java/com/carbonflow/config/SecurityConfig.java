package com.carbonflow.config;

import com.carbonflow.config.SecurityHeadersFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Stateless JWT security chain.
 *
 * <ul>
 *   <li>Public: health, login, register, refresh and logout (the auth endpoints
 *       are unauthenticated by contract — registration precedes approval,
 *       refresh/logout authenticate through the refresh token in the body).
 *       Everything else under {@code /api/v1} requires a principal; individual
 *       endpoints then enforce their permission via {@code @PreAuthorize}.</li>
 *   <li>CORS uses the explicit origin allow-list from configuration — never
 *       {@code *} together with credentials.</li>
 *   <li>401/403 responses use the platform envelope
 *       {@code {success:false, error:{code,message}}} with the Node reference
 *       backend's messages.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ObjectMapper objectMapper,
                          @Value("${carbonflow.cors.allowed-origins:}") String allowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityHeadersFilter securityHeadersFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        // Refresh is authenticated by the refresh token in the body
                        // itself (Node parity): no bearer token required.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/health", "/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        // Anything outside the API surface (static assets, error page)
                        // is public, mirroring the Node backend's static serving.
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(securityHeadersFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Password hashing: BCrypt with cost 10, matching the Node reference
     * backend (bcryptjs cost 10) so credential handling is interchangeable.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return corsConfigurationSource(allowedOrigins);
    }

    /**
     * Builds the single CORS allow-list from the raw configured value.
     *
     * <p>Phase 10.4.1 finding 5. The allow-list is <b>environment-owned</b>:
     * {@code carbonflow.cors.allowed-origins} resolves from
     * {@code CARBONFLOW_CORS_ALLOWED_ORIGINS} and has <b>no default</b>, so a
     * deployment that never configures it trusts no browser origin at all
     * (fail-closed — same-origin and server-to-server traffic are unaffected,
     * and {@code Access-Control-Allow-Origin} is simply never emitted).
     * The localhost origins that local React development needs live in the
     * {@code dev} profile ({@code application-dev.properties}) instead of the
     * shipped default, so {@code http://localhost:3000} — the retired
     * Node/Express origin — is no longer implicitly trusted anywhere.
     *
     * <p>{@code allowCredentials} is always {@code true} (the API is
     * cookie/Bearer authenticated and the React client needs it), so
     * {@code *} is refused outright: a wildcard combined with credentials is
     * rejected by browsers and is a well-known misconfiguration. Startup fails
     * rather than silently degrading.
     */
    static CorsConfigurationSource corsConfigurationSource(String rawAllowedOrigins) {
        List<String> origins = Arrays.stream(
                        (rawAllowedOrigins == null ? "" : rawAllowedOrigins).split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "carbonflow.cors.allowed-origins must not contain '*': the CarbonFlow API sends "
                            + "credentials, and wildcard origins cannot be combined with credentials. "
                            + "List the exact frontend origins instead "
                            + "(comma-separated), e.g. https://app.example.com.");
        }

        if (origins.isEmpty()) {
            log.warn("CORS allow-list is empty (carbonflow.cors.allowed-origins / "
                    + "CARBONFLOW_CORS_ALLOWED_ORIGINS is unset). Cross-origin browser requests are "
                    + "refused. Set CARBONFLOW_CORS_ALLOWED_ORIGINS to the deployed frontend origin(s), "
                    + "or start with the 'dev' profile for local React development.");
        } else {
            log.info("CORS allow-list: {} origin(s) configured; '*' is never accepted.", origins.size());
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** 401 responses in the platform envelope shape (Node's exact message). */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
                writeEnvelope(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Missing or malformed Authorization header.");
    }

    /**
     * 403 responses in the platform envelope shape. Node reveals the caller's
     * own role and the missing permission; Spring does not pass the failing
     * expression to the handler, so the message stays generic (documented
     * deviation — code and status match).
     */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeEnvelope(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Insufficient permissions for this operation.");
    }

    private void writeEnvelope(HttpServletResponse response, HttpStatus status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("success", false, "error", Map.of("code", code, "message", message)));
    }
}
