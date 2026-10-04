package com.misfinanzas.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    private static final String PHONE = "192.168.1.50";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-24T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final TestClock clock = new TestClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 15, clock);

    private void fail(String address, int times) {
        for (int i = 0; i < times; i++) limiter.failed(address);
    }

    @Test
    void fourWrongPasswordsAreFineTheFifthLocksForFifteenMinutes() {
        fail(PHONE, 4);
        assertThat(limiter.lockedFor(PHONE)).isZero();

        fail(PHONE, 1);

        assertThat(limiter.lockedFor(PHONE)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theLockEndsOnItsOwnAndTheCountStartsAgain() {
        fail(PHONE, 5);
        clock.advance(Duration.ofMinutes(15));

        assertThat(limiter.lockedFor(PHONE)).isZero();
        fail(PHONE, 4);
        assertThat(limiter.lockedFor(PHONE)).isZero();
    }

    @Test
    void aCorrectPasswordClearsTheCount() {
        fail(PHONE, 4);
        limiter.succeeded(PHONE);
        fail(PHONE, 4);

        assertThat(limiter.lockedFor(PHONE)).isZero();
    }

    @Test
    void failuresFarApartDontAddUp() {
        fail(PHONE, 4);
        clock.advance(Duration.ofMinutes(16));
        fail(PHONE, 1);

        assertThat(limiter.lockedFor(PHONE)).isZero();
    }

    @Test
    void onlyTheGuessingAddressIsLocked() {
        fail(PHONE, 5);

        assertThat(limiter.lockedFor("192.168.1.60")).isZero();
    }

    @Test
    void theMachineItselfIsNeverLockedOut() {
        fail("127.0.0.1", 50);
        fail("0:0:0:0:0:0:0:1", 50);

        assertThat(limiter.lockedFor("127.0.0.1")).isZero();
        assertThat(limiter.lockedFor("0:0:0:0:0:0:0:1")).isZero();
    }
}
