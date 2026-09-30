package com.carbonflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 9 authentication hardening — failed-login throttling (threat #1).
 *
 * <p>Login answers the same {@code INVALID_CREDENTIALS} for an unknown user,
 * a wrong password and a deactivated account, so an attacker can spray
 * passwords without learning anything — but nothing until now stopped
 * <em>volume</em>: credential stuffing, password spraying and simple
 * brute force were unmitigated (carried gap since Phase 3 / ADR-013).
 *
 * <p><b>Behavior.</b> Failures are counted per normalized account key. After
 * {@code max-failures} failures inside {@code window} the account is locked for
 * {@code lockout}; a successful sign-in clears the counter. While locked the
 * login endpoint answers {@code 429 AUTH_THROTTLED} <em>before</em> the password
 * is verified, so the lock applies to unknown accounts as well (no
 * user-enumeration oracle) and costs the attacker one BCrypt-equivalent
 * round-trip at most.
 *
 * <p><b>Why in-memory.</b> Persisting counters would need a schema change,
 * and Phase 9 policy prefers application-level solutions while V1–V8 stay
 * frozen. Consequence to know when deploying: counters are per JVM, so a
 * multi-instance deployment throttles per instance (still effective — the
 * limit is a floor, not a global quota) and restarting the process clears
 * them. A shared store is the natural follow-up if a global quota is ever
 * required.
 *
 * <p><b>Tuning.</b> Defaults (5 failures / 15 min window / 15 min lockout) are
 * deliberately generous for enterprise users (a fat-fingered password must
 * never strand a colleague mid-entry) while still capping an online attack to a
 * handful of guesses per quarter hour. Every value is environment-driven; set
 * {@code max-failures=0} to disable throttling entirely.
 */
@Component
public class LoginThrottle {

    private final int maxFailures;
    private final Duration window;
    private final Duration lockout;
    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** One account's current failure streak. */
    private static final class Attempt {
        private int failures;
        private Instant firstFailureAt;
        private Instant lockedUntil;

        Attempt(Instant firstFailureAt) {
            this.firstFailureAt = firstFailureAt;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottle(
            @Value("${carbonflow.auth.throttle.max-failures:5}") int maxFailures,
            @Value("${carbonflow.auth.throttle.window-ms:900000}") long windowMs,
            @Value("${carbonflow.auth.throttle.lockout-ms:900000}") long lockoutMs) {
        this(maxFailures, windowMs, lockoutMs, Clock.systemUTC());
    }

    /** Test seam — lets tests control time without sleeping. */
    public LoginThrottle(int maxFailures, long windowMs, long lockoutMs, Clock clock) {
        if (maxFailures < 0) {
            throw new IllegalStateException(
                    "carbonflow.auth.throttle.max-failures must be zero (disabled) or positive.");
        }
        if (windowMs <= 0 || lockoutMs <= 0) {
            throw new IllegalStateException(
                    "carbonflow.auth.throttle.window-ms and lockout-ms must be positive.");
        }
        this.maxFailures = maxFailures;
        this.window = Duration.ofMillis(windowMs);
        this.lockout = Duration.ofMillis(lockoutMs);
        this.clock = clock;
    }

    /** Normalized account key — case/whitespace insensitive. */
    public String keyFor(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Remaining lockout in seconds, or {@code 0} when the account may try. */
    public long remainingLockSeconds(String key) {
        if (maxFailures == 0) {
            return 0L;
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil == null) {
            return 0L;
        }
        Instant now = clock.instant();
        if (!now.isBefore(attempt.lockedUntil)) {
            return 0L;
        }
        return Duration.between(now, attempt.lockedUntil).toSeconds();
    }

    /** True when the account is currently locked out. */
    public boolean isLocked(String key) {
        return remainingLockSeconds(key) > 0L;
    }

    /**
     * Throws {@code 429 AUTH_THROTTLED} when the account is locked.
     * Called before the password is verified.
     */
    public void assertNotLocked(String key) {
        long remaining = remainingLockSeconds(key);
        if (remaining > 0L) {
            throw new AuthException("AUTH_THROTTLED",
                    "Too many failed sign-in attempts. Try again later.",
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /** Records one failed attempt; locks the account once the limit is reached. */
    public void recordFailure(String key) {
        if (maxFailures == 0) {
            return;
        }
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            Attempt attempt = existing;
            if (attempt == null || attempt.firstFailureAt == null
                    || now.isAfter(attempt.firstFailureAt.plus(window))) {
                // First failure, or the previous streak aged out of the window.
                attempt = new Attempt(now);
            }
            attempt.failures += 1;
            if (attempt.failures >= maxFailures) {
                attempt.lockedUntil = now.plus(lockout);
            }
            return attempt;
        });
        prune(now);
    }

    /** Clears the streak after a successful sign-in. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /** Test/ops helper — drops all counters. */
    public void reset() {
        attempts.clear();
    }

    /**
     * Bounded-memory sweep: removes streaks that are neither locked nor
     * inside their window, so a spray across many addresses cannot grow the
     * map without limit.
     */
    private void prune(Instant now) {
        if (attempts.size() < 10_000) {
            return;
        }
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next().getValue();
            boolean locked = attempt.lockedUntil != null && now.isBefore(attempt.lockedUntil);
            boolean inWindow = now.isBefore(attempt.firstFailureAt.plus(window));
            if (!locked && !inWindow) {
                iterator.remove();
            }
        }
    }
}
