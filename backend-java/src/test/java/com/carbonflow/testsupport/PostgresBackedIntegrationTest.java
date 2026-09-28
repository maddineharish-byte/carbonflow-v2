package com.carbonflow.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration tests that need the full application wired
 * against PostgreSQL: one hermetic embedded server per JVM (zonky), Flyway
 * V1..V8 applied, demo identities seeded (the {@code dbtest} profile sets
 * {@code carbonflow.seed.demo-data=true}). The Spring context is shared by all
 * subclasses, so migrations and seeds execute exactly once per test run.
 *
 * <p>Tests must create their own throwaway identities (unique emails, fresh
 * organizations) instead of mutating the seeded rows — the seeds are shared,
 * read-only fixtures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dbtest")
public abstract class PostgresBackedIntegrationTest {

    @DynamicPropertySource
    static void embeddedDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPg::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPg::username);
        registry.add("spring.datasource.password", EmbeddedPg::password);
    }

    /** Refresh-token HMAC key of the running context (test mirrors production hashing). */
    @Value("${carbonflow.auth.refresh-secret}")
    protected String refreshSecret;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Status + parsed envelope of an arbitrarily failing/succeeding call. */
    public record Api(int status, JsonNode body) {
        public String errorCode() {
            return body.path("error").path("code").asText();
        }

        public String errorMessage() {
            return body.path("error").path("message").asText();
        }
    }

    /** POST without status expectations — for tests that assert error envelopes. */
    protected Api postJson(String uri, String bearerToken, String body) throws Exception {
        var request = post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    /** GET without status expectations. */
    protected Api getJson(String uri, String bearerToken) throws Exception {
        var request = get(uri);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    /** PUT without status expectations — for update envelopes and error cases. */
    protected Api putJson(String uri, String bearerToken, String body) throws Exception {
        var request = put(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    /** DELETE without status expectations — for delete envelopes and 404/405 cases. */
    protected Api deleteJson(String uri, String bearerToken) throws Exception {
        var request = delete(uri);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    /** Multipart POST (Phase 5 evidence vault): optional form fields + one file part. */
    protected Api postMultipart(String uri, String bearerToken,
                                java.util.Map<String, String> params,
                                String fileName, String contentType, byte[] content)
            throws Exception {
        var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(uri);
        if (params != null) {
            params.forEach(builder::param);
        }
        if (fileName != null) {
            builder.file(new org.springframework.mock.web.MockMultipartFile(
                    "file", fileName, contentType, content == null ? new byte[0] : content));
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Api(result.getResponse().getStatus(),
                body == null || body.isBlank() ? objectMapper.createObjectNode()
                        : objectMapper.readTree(body));
    }

    /** Raw GET for byte-stream responses (evidence download). */
    protected MvcResult rawGet(String uri, String bearerToken) throws Exception {
        var request = get(uri);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Performs a login and returns the parsed success envelope (asserts 200). */
    protected JsonNode login(String email, String password) throws Exception {
        Api api = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (api.status() != 200) {
            throw new AssertionError("Expected 200 login for " + email
                    + " but got " + api.status() + ": " + api.body());
        }
        return api.body();
    }

    protected String loginToken(String email, String password) throws Exception {
        return login(email, password).get("data").get("accessToken").asText();
    }

    protected String loginRefreshToken(String email, String password) throws Exception {
        return login(email, password).get("data").get("refreshToken").asText();
    }

    /** Hex HMAC-SHA256 — mirrors AuthService.hashRefreshToken for row assertions. */
    protected String hashRefreshToken(String rawRefreshToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(refreshSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable in tests", e);
        }
    }
}
