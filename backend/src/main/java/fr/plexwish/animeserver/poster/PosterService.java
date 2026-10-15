package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.poster.PosterDownloader.Image;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Affiches locales (ARCHITECTURE §17). Source voulue pour un animé : l'affiche TMDB si la fiche TMDB a moins de
 * 6 mois, sinon celle d'AniList. Un animé à la fois, état en base (idempotent, reprenable) : on ne télécharge que
 * ce qui manque ou a changé, et une image déjà présente pour la même source est réutilisée sans appel réseau.
 * Affiches TMDB : retéléchargées après 5 mois, effacées à 6 (conditions de l'API TMDB).
 */
@ApplicationScoped
public class PosterService {

    private static final Logger LOG = Logger.getLogger(PosterService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ATTEMPTS = 5;

    public sealed interface Step permits Done, Idle, Unavailable {
    }

    public record Done(long animeId, String what) implements Step {
    }

    public record Idle() implements Step {
    }

    public record Unavailable(String message, Duration retryAfter) implements Step {
    }

    /** URL d'affiche pour l'API : locale (/api/posters/…) si le fichier est là, sinon distante, sinon null. */
    public record Urls(String small, String large, boolean local) {
    }

    @Inject
    PosterRepository repository;
    @Inject
    PosterDownloader downloader;
    @Inject
    PosterStore store;
    @Inject
    PosterConfig config;

    private volatile String lastUnavailable;
    private volatile Instant pausedUntil;

    public Optional<String> lastUnavailable() {
        return Optional.ofNullable(lastUnavailable);
    }

    public Optional<Instant> pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(Instant.now()) ? Optional.of(p) : Optional.empty();
    }

    private long tmdbMaxAge() {
        return config.tmdbMaxAge().toSeconds();
    }

    private String tmdbImageBase() {
        return config.tmdbImageBase().replaceAll("/+$", "");
    }

    // --- Tâche de fond -------------------------------------------------------------------------------

    public Step processNext() throws SQLException {
        expireTmdb();
        PosterRepository.Next next = repository.next(tmdbMaxAge(), tmdbImageBase(), config.tmdbRefreshAfter().toSeconds())
                .orElse(null);
        if (next == null) {
            return new Idle();
        }
        try {
            String what = fetch(next.animeId(), next.provider(), next.src());
            lastUnavailable = null;
            return new Done(next.animeId(), what);
        } catch (ProviderUnavailableException e) {
            Duration wait = e.retryAfter().orElse(config.unavailablePause());
            lastUnavailable = e.getMessage();
            pausedUntil = Instant.now().plus(wait);
            LOG.infof("Affiches : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
            return new Unavailable(e.getMessage(), wait);
        } catch (PosterDownloader.Rejected e) {
            LOG.infof("Affiches : affiche de l'animé %d refusée (%s)", next.animeId(), e.getMessage());
            failed(next.animeId(), next.provider(), next.src(), e.getMessage(), true);
            return new Done(next.animeId(), "REJECTED");
        } catch (PosterDownloader.Retry e) {
            failed(next.animeId(), next.provider(), next.src(), e.getMessage(), false);
            return new Done(next.animeId(), "RETRY");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Affiches : échec pour l'animé %d", next.animeId());
            failed(next.animeId(), next.provider(), next.src(), e.toString(), false);
            return new Done(next.animeId(), "ERROR");
        }
    }

    /** Une image déjà présente pour la même source est réutilisée (pas de nouvel appel), sinon téléchargement. */
    String fetch(long animeId, String provider, String src)
            throws SQLException, ProviderUnavailableException, PosterDownloader.Rejected, PosterDownloader.Retry {
        Optional<PosterRepository.Stored> stored = repository.sameSource(src, animeId, config.tmdbRefreshAfter().toSeconds());
        if (stored.isPresent() && store.existing(stored.get().relativePath()).isPresent()) {
            PosterRepository.Stored r = stored.get();
            save(animeId, provider, src, r.relativePath(), r.sha256(), r.contentType(), r.bytes(), r.fetchedAt());
            return "REUSED";
        }
        Image image = downloader.download(src);
        String relative = PosterStore.relativePath(image.sha256(), image.extension());
        store.write(relative, image.bytes());
        save(animeId, provider, src, relative, image.sha256(), image.contentType(), image.bytes().length,
                OffsetDateTime.now(ZoneOffset.UTC));
        return "DOWNLOADED";
    }

    private void save(long animeId, String provider, String src, String relative, String sha, String type, long bytes,
                      OffsetDateTime fetchedAt) throws SQLException {
        String oldPath = null;
        String publicId = null;
        Optional<PosterRepository.Current> current = repository.current(animeId);
        if (current.isPresent()) {
            oldPath = current.get().relativePath();
            // Même image : même URL (cache navigateur conservé) ; image différente : nouvelle URL.
            publicId = sha.equals(current.get().sha256()) ? current.get().publicId() : null;
        }
        repository.saveOk(animeId, new PosterRepository.Saved(provider, src, publicId != null ? publicId : newPublicId(), relative,
                sha, type, bytes, fetchedAt));
        if (oldPath != null && !oldPath.equals(relative)) {
            deleteIfUnreferenced(List.of(oldPath));
        }
    }

    /**
     * Échec : refus définitif, ou échecs passagers répétés ({@value #MAX_ATTEMPTS}) → cette source n'est plus
     * retentée tant qu'elle ne change pas. Une affiche locale déjà présente reste servie.
     */
    private void failed(long animeId, String provider, String src, String error, boolean permanent) throws SQLException {
        String message = error.length() > 300 ? error.substring(0, 300) : error;
        repository.saveFailure(animeId, provider, src, message, permanent, MAX_ATTEMPTS);
    }

    /** Conditions TMDB : affiche TMDB de plus de 6 mois, ou dont la fiche TMDB a disparu → effacée (fichier compris). */
    int expireTmdb() throws SQLException {
        List<String> paths = repository.deleteExpiredTmdb(tmdbMaxAge());
        deleteIfUnreferenced(paths);
        return paths.size();
    }

    /** Fin de licence TMDB : toutes les affiches TMDB effacées, fichiers compris. */
    public int purgeTmdb() throws SQLException {
        List<String> deleted = repository.deleteAllTmdb();
        int n = deleted.size();
        List<String> paths = deleted.stream().filter(java.util.Objects::nonNull).toList();
        deleteIfUnreferenced(paths);
        return n;
    }

    private void deleteIfUnreferenced(Collection<String> paths) throws SQLException {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> referenced = referenced();
        for (String p : new HashSet<>(paths)) {
            if (!referenced.contains(p)) {
                store.delete(p);
            }
        }
    }

    public Set<String> referenced() throws SQLException {
        return repository.referenced();
    }

    /** Fichier local disparu (effacé à la main, disque changé) : on oublie le chemin, la tâche le retélécharge. */
    public void markMissing(long animeId) throws SQLException {
        repository.markMissing(animeId);
    }

    /** Retélécharger (admin) : on oublie les échecs ; l'affiche locale actuelle reste servie en attendant. */
    public void requeue(long animeId) throws SQLException {
        repository.requeue(animeId);
    }

    // --- URL pour l'API ------------------------------------------------------------------------------

    /** Repli : fichier local, puis URL distante (TMDB, sinon AniList), puis rien (visuel de remplacement). */
    public Map<Long, Urls> urls(Collection<Long> animeIds) {
        Map<Long, Urls> out = new HashMap<>();
        if (animeIds.isEmpty()) {
            return out;
        }
        List<Long> missing = new ArrayList<>();
        try {
            for (PosterRepository.UrlRow r : repository.urlRows(animeIds, tmdbMaxAge(), tmdbImageBase())) {
                long id = r.animeId();
                String publicId = r.publicId();
                String relative = r.relativePath();
                String tmdb = r.tmdbUrl();
                String small = r.posterUrl();
                String large = r.posterLargeUrl();
                // Source voulue aujourd'hui (même règle que PosterRepository.next) : la copie locale n'est servie que si elle en
                // vient. Après une correction manuelle, la nouvelle image s'affiche donc tout de suite (chargée à
                // la source), le temps que la tâche de fond télécharge la nouvelle copie.
                String desired = tmdb != null ? tmdb : (large != null ? large : small);
                boolean current = desired != null && desired.equals(r.sourceUrl());
                if (publicId != null && relative != null && r.localAllowed() && current) {
                    if (store.existing(relative).isPresent()) {
                        String local = "/api/posters/" + publicId;
                        out.put(id, new Urls(local, local, true));
                        continue;
                    }
                    missing.add(id);
                }
                if (tmdb != null) {
                    out.put(id, new Urls(tmdb, tmdb, false));
                } else if (small != null || large != null) {
                    out.put(id, new Urls(small != null ? small : large, large != null ? large : small, false));
                }
            }
            for (long id : missing) {
                markMissing(id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Fichier servi pour /api/posters/{publicId} (jamais un chemin venu du client). */
    public record Served(long animeId, java.nio.file.Path file, String contentType, String sha256) {
    }

    public Optional<Served> served(String publicId) throws SQLException {
        Optional<PosterRepository.ServedRow> row = repository.served(publicId, tmdbMaxAge());
        if (row.isEmpty() || !PosterDownloader.TYPES.containsKey(row.get().contentType())) {
            return Optional.empty();
        }
        PosterRepository.ServedRow r = row.get();
        Optional<java.nio.file.Path> file = store.existing(r.relativePath());
        if (file.isEmpty()) {
            markMissing(r.animeId());
            return Optional.empty();
        }
        return Optional.of(new Served(r.animeId(), file.get(), r.contentType(), r.sha256()));
    }

    /** État de chaque animé, pour l'admin : source voulue et affiche locale. */
    public record State(long animeId, String title, String provider, String src, String status, String localProvider,
                        String relative, Long bytes, Instant fetchedAt, String failedSource, String lastError,
                        boolean localAllowed) {
        public boolean failed() {
            return failedSource != null && failedSource.equals(src);
        }
    }

    public List<State> states() throws SQLException {
        return repository.states(tmdbMaxAge(), tmdbImageBase());
    }

    static String newPublicId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Pour la tâche : ménage des fichiers temporaires et orphelins. */
    public int sweep() throws SQLException {
        return store.sweep(referenced(), Duration.ofMinutes(10));
    }
}
