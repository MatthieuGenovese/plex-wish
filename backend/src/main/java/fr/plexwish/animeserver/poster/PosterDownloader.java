package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.RateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Télécharge une affiche en refusant tout ce qui n'est pas une image raisonnable : hôte hors liste, redirection
 * (jamais suivie), type de contenu, signature du fichier (octets magiques), taille.
 */
@ApplicationScoped
public class PosterDownloader {

    /** Image téléchargée et vérifiée. */
    public record Image(byte[] bytes, String contentType, String extension, String sha256) {
    }

    /** Refus définitif pour cette URL (pas d'image, trop grosse, hôte interdit, 404…) : pas de nouvel essai. */
    public static class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /** Échec passager pour cette URL (HTTP 5xx, transfert coupé) : nouvel essai plus tard, abandon après 5. */
    public static class Retry extends Exception {
        public Retry(String message) {
            super(message);
        }
    }

    static final Map<String, String> TYPES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final PosterConfig config;
    private final RateLimiter limiter;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NEVER).build();

    @Inject
    public PosterDownloader(PosterConfig config) {
        this.config = config;
        this.clock = Clock.systemUTC();
        this.limiter = new RateLimiter(config.minInterval(), clock, d -> Thread.sleep(d.toMillis()));
    }

    /** Hôte autorisé (liste fixe), schéma https (http seulement en test), sans identifiants dans l'URL. */
    public boolean allowed(String url) {
        return check(url).isEmpty();
    }

    private Optional<String> check(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException e) {
            return Optional.of("URL invalide");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(config.allowHttp() && scheme.equals("http"))) {
            return Optional.of("schéma refusé");
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null
                || config.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
            return Optional.of("hôte non autorisé (" + uri.getHost() + ")");
        }
        return Optional.empty();
    }

    public Image download(String url) throws Rejected, Retry, ProviderUnavailableException {
        Optional<String> refused = check(url);
        if (refused.isPresent()) {
            throw new Rejected(refused.get());
        }
        HttpResponse<InputStream> response;
        try {
            limiter.acquire();
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "image/webp,image/jpeg,image/png").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrompu", null);
        } catch (IOException e) {
            throw new ProviderUnavailableException("serveur d'images injoignable : " + e.getClass().getSimpleName(), null);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                throw new Rejected("redirection refusée (HTTP " + status + ")");
            }
            if (status == 429) {
                Duration wait = response.headers().firstValue("Retry-After").flatMap(PosterDownloader::seconds)
                        .orElse(Duration.ofSeconds(30));
                limiter.pauseUntil(clock.instant().plus(wait));
                throw new ProviderUnavailableException("serveur d'images : limite de débit atteinte", wait);
            }
            if (status == 404 || status == 410 || status == 403) {
                throw new Rejected("HTTP " + status);
            }
            if (status != 200) {
                throw new Retry("HTTP " + status);
            }
            String type = response.headers().firstValue("Content-Type").orElse("")
                    .split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!TYPES.containsKey(type)) {
                throw new Rejected("pas une image acceptée (" + (type.isEmpty() ? "type absent" : type) + ")");
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > config.maxBytes()) {
                throw new Rejected("image trop grosse (" + declared + " octets)");
            }
            byte[] bytes = readAtMost(body, config.maxBytes());
            String detected = detect(bytes);
            if (detected == null) {
                throw new Rejected("le contenu n'est pas une image JPEG, PNG ou WebP");
            }
            if (!detected.equals(type)) {
                throw new Rejected("type annoncé (" + type + ") différent du contenu (" + detected + ")");
            }
            return new Image(bytes, detected, TYPES.get(detected), sha256(bytes));
        } catch (IOException e) {
            throw new Retry("téléchargement interrompu : " + e.getClass().getSimpleName());
        }
    }

    private static byte[] readAtMost(InputStream in, long max) throws IOException, Rejected {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new Rejected("image trop grosse (plus de " + max + " octets)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Type réel d'après les premiers octets (JPEG FF D8 FF, PNG 89 50 4E 47 0D 0A 1A 0A, WebP RIFF….WEBP). */
    static String detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    static String sha256(byte[] bytes) {
        try {
            return PosterStore.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<Duration> seconds(String s) {
        try {
            return Optional.of(Duration.ofSeconds(Math.max(1, Long.parseLong(s.trim()))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
