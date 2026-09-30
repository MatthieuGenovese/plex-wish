package fr.plexwish.animeserver.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fenêtres et durées de blocage, avec une horloge contrôlée. */
class LoginAttemptLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter =
            new LoginAttemptLimiter(5, 20, Duration.ofMinutes(15), Duration.ofMinutes(15), clock);

    @Test
    void blockLastsFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.1.1.1", "bob");
        }
        assertTrue(limiter.isBlocked("1.1.1.1", "bob"));
        assertTrue(limiter.isBlocked("1.1.1.1", "BOB ")); // identifiant normalisé
        assertFalse(limiter.isBlocked("2.2.2.2", "bob"));
        clock.advance(Duration.ofMinutes(14));
        assertTrue(limiter.isBlocked("1.1.1.1", "bob"));
        clock.advance(Duration.ofMinutes(2));
        assertFalse(limiter.isBlocked("1.1.1.1", "bob"));
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.1.1.1", "bob");
        }
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure("1.1.1.1", "bob");
        assertFalse(limiter.isBlocked("1.1.1.1", "bob"));
    }

    @Test
    void ipCounterSpansUsernames() {
        for (int i = 0; i < 19; i++) {
            limiter.recordFailure("3.3.3.3", "user" + i);
        }
        assertFalse(limiter.isBlocked("3.3.3.3", "someone"));
        limiter.recordFailure("3.3.3.3", "user19");
        assertTrue(limiter.isBlocked("3.3.3.3", "someone"));
        assertFalse(limiter.isBlocked("4.4.4.4", "someone"));
    }
}
