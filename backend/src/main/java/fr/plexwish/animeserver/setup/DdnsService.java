package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.auth.AuthConfig;
import fr.plexwish.animeserver.common.BackgroundLoop;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Nom de domaine dynamique DuckDNS (D1.3) : l'adresse publique de la box de l'ami peut changer ; toutes les 5 minutes,
 * le serveur dit à DuckDNS de faire pointer le nom vers l'adresse d'où il appelle (paramètre ip vide : DuckDNS prend
 * l'adresse IPv4 de l'appelant, https://www.duckdns.org/spec.jsp). Le nom vient de PUBLIC_URL (xxx.duckdns.org) ; le
 * jeton est un secret (fichier 600). L'URL appelée contient le jeton : elle n'est JAMAIS journalisée ni renvoyée.
 */
@ApplicationScoped
public class DdnsService extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(DdnsService.class);
    static final String SUFFIX = ".duckdns.org";
    /** Jeton DuckDNS : un UUID. */
    private static final Pattern TOKEN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record Status(String domain, boolean supported, boolean configured, Instant lastAttempt, Boolean lastOk,
                         String ip, String message) {
    }

    @Inject
    SetupConfig config;
    @Inject
    AuthConfig auth;
    @Inject
    SecretStore secrets;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Instant lastAttempt;
    private volatile Boolean lastOk;
    private volatile String lastIp;
    private volatile String lastMessage;

    public DdnsService() {
        super("ddns-update");
    }

    void onStart(@Observes StartupEvent event) {
        if (config.ddns().enabled() && subdomain().isPresent()) {
            start();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    /** « mon-anime » pour https://mon-anime.duckdns.org ; vide si l'adresse publique n'est pas chez DuckDNS. */
    public Optional<String> subdomain() {
        return auth.publicUrl().map(DdnsService::subdomainOf).filter(s -> !s.isEmpty());
    }

    static String subdomainOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!host.endsWith(SUFFIX)) {
                return "";
            }
            String rest = host.substring(0, host.length() - SUFFIX.length());
            return rest.substring(rest.lastIndexOf('.') + 1); // a.b.duckdns.org : le nom DuckDNS est « b »
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public Status status() {
        String domain = auth.publicUrl().map(u -> URI.create(u).getHost()).orElse(null);
        return new Status(domain, subdomain().isPresent(), secrets.exists(SecretStore.DDNS), lastAttempt, lastOk, lastIp, lastMessage);
    }

    /** Enregistre un nouveau jeton seulement si DuckDNS l'accepte (essai immédiat). */
    public Status setToken(String token) throws IOException {
        String t = token == null ? "" : token.trim();
        if (subdomain().isEmpty()) {
            throw new fr.plexwish.animeserver.common.ApiException(409, "DDNS_NOT_DUCKDNS",
                    "L'adresse publique ne se termine pas par .duckdns.org : pas de mise à jour automatique à configurer.");
        }
        if (!TOKEN.matcher(t).matches()) {
            throw new fr.plexwish.animeserver.common.ApiException(400, "DDNS_TOKEN_INVALID",
                    "Le jeton DuckDNS a la forme xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (page d'accueil de duckdns.org, une fois connecté).");
        }
        if (!update(t)) {
            throw new fr.plexwish.animeserver.common.ApiException(400, "DDNS_REJECTED",
                    "DuckDNS a refusé ce jeton pour ce nom (" + lastMessage + "). Vérifier le jeton et le nom de domaine.");
        }
        secrets.write(SecretStore.DDNS, t);
        if (config.ddns().enabled() && !running()) {
            start();
        }
        return status();
    }

    public void clearToken() throws IOException {
        secrets.delete(SecretStore.DDNS);
        lastOk = null;
        lastIp = null;
        lastMessage = null;
    }

    /** Un appel à DuckDNS. Vrai si accepté. */
    synchronized boolean update(String token) {
        lastAttempt = Instant.now();
        String url = config.ddns().duckdnsUrl() + "?domains=" + URLEncoder.encode(subdomain().orElseThrow(), StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&ip=&verbose=true";
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String[] lines = r.body() == null ? new String[0] : r.body().strip().split("\\R");
            if (r.statusCode() == 200 && lines.length > 0 && "OK".equals(lines[0].trim())) {
                lastOk = true;
                lastIp = lines.length > 1 && lines[1].trim().matches("[0-9.]{7,15}") ? lines[1].trim() : null;
                String change = lines.length > 3 ? lines[3].trim() : "";
                lastMessage = "UPDATED".equals(change) ? "adresse mise à jour" : "adresse inchangée";
                if ("UPDATED".equals(change)) {
                    LOG.infof("DNS dynamique : %s pointe maintenant vers %s", subdomain().get() + SUFFIX, lastIp);
                }
                return true;
            }
            lastOk = false;
            lastMessage = "réponse " + r.statusCode() + " " + (lines.length > 0 ? lines[0].trim() : "");
        } catch (IOException e) {
            lastOk = false;
            lastMessage = "DuckDNS injoignable (" + e.getClass().getSimpleName() + ")"; // jamais l'URL : elle contient le jeton
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastOk = false;
            lastMessage = "interrompu";
        }
        LOG.warnf("DNS dynamique : échec de la mise à jour (%s)", lastMessage);
        return false;
    }

    @Override
    protected Outcome step() {
        Optional<String> token = secrets.read(SecretStore.DDNS);
        if (token.isEmpty() || subdomain().isEmpty()) {
            return new Idle(Duration.ofMinutes(10));
        }
        return update(token.get()) ? new Idle(config.ddns().interval()) : new Pause(config.ddns().interval());
    }
}
