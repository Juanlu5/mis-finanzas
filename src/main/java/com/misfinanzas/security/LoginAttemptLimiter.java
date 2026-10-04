package com.misfinanzas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops password guessing from the home WiFi: after {@code maxFailures} wrong passwords in a row from one
 * address, that address is refused for {@code lockMinutes} (every other device keeps working). A correct
 * login clears the count, and so does going {@code lockMinutes} without failing. The machine itself
 * (loopback) is never locked out: whoever can reach 127.0.0.1 already has the database file, and it keeps
 * the owner from locking themselves out of their own app. Kept in memory: a restart forgets it, which only
 * gives a guesser a handful more attempts per restart they can't cause.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    @Autowired
    public LoginAttemptLimiter(@Value("${app.login.max-failures:5}") int maxFailures,
                               @Value("${app.login.lock-minutes:15}") int lockMinutes) {
        this(maxFailures, lockMinutes, Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxFailures, int lockMinutes, Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
        this.clock = clock;
    }

    /** How long {@code address} still has to wait, or {@link Duration#ZERO} if it may try. */
    public Duration lockedFor(String address) {
        Attempts a = attempts.get(address);
        if (a == null || a.lockedUntil() == null) return Duration.ZERO;
        Duration left = Duration.between(clock.instant(), a.lockedUntil());
        if (left.isNegative() || left.isZero()) {
            attempts.remove(address, a);
            return Duration.ZERO;
        }
        return left;
    }

    public void failed(String address) {
        if (isLoopback(address)) return;
        Instant now = clock.instant();
        Attempts updated = attempts.compute(address, (key, a) -> {
            // a failure long after the previous one starts the count again
            int failures = a == null || a.lastFailure().plus(lockDuration).isBefore(now) ? 1 : a.failures() + 1;
            return new Attempts(failures, now, failures >= maxFailures ? now.plus(lockDuration) : null);
        });
        if (updated.lockedUntil() != null && updated.failures() == maxFailures) {
            log.warn("{} contraseñas incorrectas seguidas desde {}: bloqueado {} minutos", maxFailures, address, lockDuration.toMinutes());
        }
    }

    public void succeeded(String address) {
        attempts.remove(address);
    }

    static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
