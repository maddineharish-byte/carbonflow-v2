package com.carbonflow.config;

import com.carbonflow.dto.ApiResponse;
import com.carbonflow.service.AuthException;
import com.carbonflow.service.AuthPersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Maps every unhandled exception onto the platform envelope
 * {@code {success:false, error:{code,message}}} — the same shape produced by
 * the Node reference backend — without leaking stack traces or internals to
 * clients. Unexpected failures are logged server-side with full detail.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Malformed JSON body — matches the Node backend's INVALID_JSON contract. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is missing or malformed JSON.");
    }

    /** Bean Validation failures on {@code @Valid} request payloads. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                details.isBlank() ? "Request validation failed." : details);
    }

    /** Type mismatches on path/query parameters (e.g. a non-UUID identifier). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "Invalid value for parameter '" + ex.getName() + "'.");
    }

    /** Wrong HTTP verb for an existing path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint.");
    }

    /** Unknown API path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    /**
     * Permission denied by method security ({@code @PreAuthorize}). Emitted as
     * the envelope body because method-level denials never reach the security
     * filter's AccessDeniedHandler.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this operation.");
    }

    /**
     * Contract failures from the auth/identity services: the Node reference
     * backend's exact status + error code (INVALID_CREDENTIALS,
     * REFRESH_TOKEN_EXPIRED, SWITCH_NOT_AUTHORIZED, ORG_NOT_FOUND, …).
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthException(AuthException ex) {
        return error(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    /** Database unreachable on an authentication path (Node: 503 AUTH_PERSISTENCE_UNAVAILABLE). */
    @ExceptionHandler(AuthPersistenceException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthPersistence(AuthPersistenceException ex) {
        log.error("Authentication persistence unavailable", ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PERSISTENCE_UNAVAILABLE", ex.getMessage());
    }

    /**
     * Database failures outside the auth paths (relevant from Phase 4 when
     * more domains become PostgreSQL-backed): 503, never a leaky 500.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex) {
        log.error("Database access failure", ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE",
                "A required data store is temporarily unavailable.");
    }

    /** Unexpected failure: full detail stays in the server log only. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. The incident has been logged.");
    }

    private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(code, message));
    }
}
