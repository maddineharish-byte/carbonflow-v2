package com.carbonflow.service;

/**
 * Wraps a Spring {@code DataAccessException} on an authentication path so it
 * surfaces with the Node reference backend's contract code
 * {@code AUTH_PERSISTENCE_UNAVAILABLE} (503) instead of a generic 500.
 * Always rolls back the surrounding transaction.
 */
public class AuthPersistenceException extends RuntimeException {

    public AuthPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
