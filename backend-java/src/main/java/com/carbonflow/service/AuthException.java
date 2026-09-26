package com.carbonflow.service;

import org.springframework.http.HttpStatus;

/**
 * Business failure carrying the exact contract code of the Node reference
 * backend (e.g. {@code INVALID_CREDENTIALS}, {@code REFRESH_TOKEN_EXPIRED}).
 * Mapped to the platform envelope by {@code ApiExceptionHandler}; thrown inside
 * {@code @Transactional} services it triggers a rollback, except where a method
 * explicitly declares {@code noRollbackFor} (token revocation must persist —
 * see {@link AuthService}).
 */
public class AuthException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AuthException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
