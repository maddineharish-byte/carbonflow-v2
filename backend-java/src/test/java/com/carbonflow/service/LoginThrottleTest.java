package com.carbonflow.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 — login throttling (threat #1: no brute-force protection).
 *
 * <p>Time is injected so the failure window, the lockout expiry and the
 * streak reset are asserted exactly instead of by sleeping.
 */
class LoginThrottleTest {

    /** Mutable clock so the tests can move time forward deterministically. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 900_000L;   // 15 minutes
    private static final long LOCKOUT_MS = 900_000L;  // 15 minutes

    private TestClock clock = new TestClock();

    private LoginThrottle throttle() {
        return new LoginThrottle(MAX_FAILURES, WINDOW_MS, LOCKOUT_MS, clock);
    }

    @Test
    void accountIsUnlockedBeforeAnyFailure() {
        LoginThrottle throttle = throttle();
        assertFalse(throttle.isLocked(throttle.keyFor("user@example.com")));
        assertEquals(0L, throttle.remainingLockSeconds(throttle.keyFor("user@example.com")));
    }

    @Test
    void locksTheAccountOnlyAfterTheConfiguredFailureBudgetIsExhausted() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");

        for (int attempt = 1; attempt < MAX_FAILURES; attempt++) {
            throttle.recordFailure(key);
            assertFalse(throttle.isLocked(key), "must stay unlocked after " + attempt + " failures");
        }
        throttle.recordFailure(key);

        assertTrue(throttle.isLocked(key), MAX_FAILURES + " failures must lock the account");
        assertTrue(throttle.remainingLockSeconds(key) > 0);
    }

    @Test
    void lockedAccountThrowsThrottledBeforeAnyPasswordCheck() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");
        for (int i = 0; i < MAX_FAILURES; i++) {
            throttle.recordFailure(key);
        }

        AuthException error = assertThrows(AuthException.class,
                () -> throttle.assertNotLocked(key));
        assertEquals("AUTH_THROTTLED", error.getCode());
        assertEquals(429, error.getStatus().value());
        assertEquals("Too many failed sign-in attempts. Try again later.", error.getMessage());
    }

    @Test
    void accountKeyIsCaseAndWhitespaceInsensitive() {
        LoginThrottle throttle = throttle();
        String canonical = throttle.keyFor("User@Example.com");

        assertEquals(canonical, throttle.keyFor("  user@example.com  "));
        assertEquals(canonical, throttle.keyFor("USER@EXAMPLE.COM"));

        for (int i = 0; i < MAX_FAILURES; i++) {
            throttle.recordFailure(throttle.keyFor("user@EXAMPLE.com"));
        }
        // A differently-cased attempt cannot sidestep the lockout.
        assertTrue(throttle.isLocked(canonical));
    }

    @Test
    void lockoutExpiresAfterTheLockoutWindow() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");
        for (int i = 0; i < MAX_FAILURES; i++) {
            throttle.recordFailure(key);
        }
        assertTrue(throttle.isLocked(key));

        clock.advance(Duration.ofMillis(LOCKOUT_MS - 1000));
        assertTrue(throttle.isLocked(key), "still locked one second before expiry");
        assertEquals(1L, throttle.remainingLockSeconds(key));

        clock.advance(Duration.ofSeconds(2));
        assertFalse(throttle.isLocked(key), "lockout must lapse after the configured window");
        assertEquals(0L, throttle.remainingLockSeconds(key));
    }

    @Test
    void successfulSignInClearsTheFailureStreak() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");

        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            throttle.recordFailure(key);
        }
        throttle.recordSuccess(key);

        // A full fresh budget is available after a success.
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            throttle.recordFailure(key);
        }
        assertFalse(throttle.isLocked(key));
    }

    @Test
    void staleFailuresOutsideTheWindowDoNotCount() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");

        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            throttle.recordFailure(key);
        }
        // Wait past the observation window, then fail again: the old streak is
        // stale and must not combine with the new one.
        clock.advance(Duration.ofMillis(WINDOW_MS + 1000));
        throttle.recordFailure(key);

        assertFalse(throttle.isLocked(key));
    }

    @Test
    void separateAccountsHaveIndependentBudgets() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < MAX_FAILURES; i++) {
            throttle.recordFailure(throttle.keyFor("attacked@example.com"));
        }

        assertTrue(throttle.isLocked(throttle.keyFor("attacked@example.com")));
        assertFalse(throttle.isLocked(throttle.keyFor("bystander@example.com")),
                "an attack on one account must never lock another");
    }

    @Test
    void throttlingCanBeDisabledWithZeroMaxFailures() {
        LoginThrottle throttle = new LoginThrottle(0, WINDOW_MS, LOCKOUT_MS, clock);
        String key = throttle.keyFor("user@example.com");

        for (int i = 0; i < 50; i++) {
            throttle.recordFailure(key);
        }
        assertFalse(throttle.isLocked(key));
        // And the guard never throws.
        throttle.assertNotLocked(key);
    }

    @Test
    void rejectsNonsensicalConfigurationAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new LoginThrottle(-1, WINDOW_MS, LOCKOUT_MS, clock));
        assertThrows(IllegalStateException.class,
                () -> new LoginThrottle(5, 0L, LOCKOUT_MS, clock));
        assertThrows(IllegalStateException.class,
                () -> new LoginThrottle(5, WINDOW_MS, -1L, clock));
    }

    @Test
    void resetClearsEveryCounter() {
        LoginThrottle throttle = throttle();
        String key = throttle.keyFor("user@example.com");
        for (int i = 0; i < MAX_FAILURES; i++) {
            throttle.recordFailure(key);
        }
        assertTrue(throttle.isLocked(key));

        throttle.reset();
        assertFalse(throttle.isLocked(key));
    }
}
