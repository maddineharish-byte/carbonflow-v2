package com.carbonflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that every error path is rendered in the platform envelope
 * {@code {success:false, error:{code,message}}} and that internal detail never
 * reaches the client.
 */
class ApiExceptionHandlerTest {

    /** Minimal controller exercising each handled failure mode. */
    @RestController
    static class FaultController {

        @PostMapping("/fault/echo")
        public ResponseEntity<?> echo(@Valid @RequestBody EchoRequest request) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/fault/boom")
        public ResponseEntity<?> boom() {
            throw new IllegalStateException("super secret internals: db password is hunter2");
        }

        @GetMapping("/fault/denied")
        public ResponseEntity<?> denied() {
            throw new AccessDeniedException("denied for role MANAGEMENT");
        }

        @GetMapping("/fault/type/{id}")
        public ResponseEntity<?> typed(@PathVariable("id") int id) {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/fault/create")
        public ResponseEntity<?> create() {
            return ResponseEntity.ok().build();
        }
    }

    public static class EchoRequest {
        @NotBlank(message = "name must not be blank")
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FaultController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonYieldsInvalidJsonEnvelope() throws Exception {
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_JSON"));
    }

    @Test
    void beanValidationYieldsValidationErrorEnvelopeWithFieldDetail() throws Exception {
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void unexpectedFailureYieldsGenericEnvelopeWithoutInternals() throws Exception {
        mockMvc.perform(get("/fault/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    @Test
    void accessDeniedYieldsForbiddenEnvelope() throws Exception {
        mockMvc.perform(get("/fault/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void pathVariableTypeMismatchYieldsInvalidArgumentEnvelope() throws Exception {
        mockMvc.perform(get("/fault/type/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void unsupportedMethodYieldsMethodNotAllowedEnvelope() throws Exception {
        mockMvc.perform(get("/fault/create"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    // ------------------------------------------------------------------
    // Phase 10.4.1 finding 3: unsupported media type must be 415, not 500.
    // ------------------------------------------------------------------

    @Test
    void unsupportedContentTypeYields415Envelope() throws Exception {
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("name=acme"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unsupportedXmlContentTypeYields415Envelope() throws Exception {
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<name>acme</name>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void supportedJsonContentTypeStillSucceeds() throws Exception {
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonStillYields400Not415() throws Exception {
        // Malformed JSON is a 400 (INVALID_JSON); only the media type decides
        // between 400 and 415. Guards against a too-broad handler.
        mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_JSON"));
    }

    @Test
    void unsupportedMediaTypeEnvelopeLeaksNoInternals() throws Exception {
        MvcResult result = mockMvc.perform(post("/fault/echo")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("name=acme"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("org.springframework"),
                "no framework class names may reach the client: " + body);
        assertFalse(body.contains("com.carbonflow"),
                "no application class names may reach the client: " + body);
        assertFalse(body.contains("Exception"),
                "no exception type names may reach the client: " + body);
        assertFalse(body.contains("at "),
                "no stack frames may reach the client: " + body);
    }

    @Test
    void unrelatedFailureIsNotReclassifiedByTheMediaTypeHandler() throws Exception {
        // The 500 branch must stay a 500: a blanket 400/415 catch is forbidden.
        mockMvc.perform(get("/fault/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }
}
