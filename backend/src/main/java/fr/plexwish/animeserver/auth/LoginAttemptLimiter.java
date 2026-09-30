package fr.plexwish.animeserver.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti brute force en mémoire (ARCHITECTURE §5.4). Deux compteurs, aucun par identifiant seul :
 * <ul>
 *     <li>(IP, identifiant) : bloque ce couple après {@code maxPerIpAndLogin} échecs ;</li>
 *     <li>IP seule : bloque l'IP après {@code maxPerIp} échecs, tous identifiants confondus.</li>
 * </ul>
 * Un attaquant ne bloque donc que ses propres IP : l'admin légitime se connecte depuis la sienne.
 * Remis à zéro au redémarrage (acceptable pour ~10 utilisateurs).
 */
@ApplicationScoped
public class LoginAttemptLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerPair;
    private final int maxPerIp;
    private final long windowMillis;
    private final long blockMillis;
    private final Clock clock;

    @Inject
    public LoginAttemptLimiter(AuthConfig config) {
        this(config.loginLimits().maxPerIpAndLogin(), config.loginLimits().maxPerIp(),
                Duration.ofMinutes(config.loginLimits().windowMinutes()),
                Duration.ofMinutes(config.loginLimits().blockMinutes()), Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxPerPair, int maxPerIp, Duration window, Duration block, Clock clock) {
        this.maxPerPair = maxPerPair;
        this.maxPerIp = maxPerIp;
        this.windowMillis = window.toMillis();
        this.blockMillis = block.toMillis();
        this.clock = clock;
    }

    public boolean isBlocked(String ip, String login) {
        long now = clock.millis();
        return blocked(pairKey(ip, login), now) || blocked(ipKey(ip), now);
    }

    public void recordFailure(String ip, String login) {
        long now = clock.millis();
        fail(pairKey(ip, login), maxPerPair, now);
        fail(ipKey(ip), maxPerIp, now);
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.expired(now, windowMillis));
        }
    }

    /** Un succès remet à zéro le couple (IP, identifiant), pas le compteur de l'IP. */
    public void recordSuccess(String ip, String login) {
        windows.remove(pairKey(ip, login));
    }

    private boolean blocked(String key, long now) {
        Window w = windows.get(key);
        return w != null && w.blockedUntil > now;
    }

    private void fail(String key, int max, long now) {
        windows.compute(key, (k, w) -> {
            if (w == null || w.expired(now, windowMillis)) {
                w = new Window(now);
            }
            w.count++;
            if (w.count >= max) {
                w.blockedUntil = now + blockMillis;
                w.count = 0;
                w.start = now;
            }
            return w;
        });
    }

    private static String pairKey(String ip, String login) {
        return "pair|" + ip + "|" + login.trim().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return "ip|" + ip;
    }

    private static final class Window {
        long start;
        int count;
        long blockedUntil;

        Window(long start) {
            this.start = start;
        }

        boolean expired(long now, long windowMillis) {
            return now - start > windowMillis && blockedUntil <= now;
        }
    }
}
