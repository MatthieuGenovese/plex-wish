package fr.plexwish.animeserver.cast;

import com.fasterxml.jackson.databind.JsonNode;
import fr.plexwish.animeserver.metadata.AniListProvider;
import fr.plexwish.animeserver.metadata.MetadataConfig;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.MetadataService;
import fr.plexwish.animeserver.poster.PosterConfig;
import fr.plexwish.animeserver.poster.PosterDownloader;
import fr.plexwish.animeserver.poster.PosterStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Distribution des animés (ARCHITECTURE §19). Source unique : AniList, à partir des appariements existants
 * (jamais de nouvel appariement), seulement pour les animés de la bibliothèque. Un animé à la fois, état en base
 * (idempotent, reprenable). Les requêtes AniList passent après les métadonnées : rien n'est demandé tant que la
 * tâche des métadonnées a du travail, et le limiteur (30 requêtes/min) est le même.
 */
@ApplicationScoped
public class CastService {

    private static final Logger LOG = Logger.getLogger(CastService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String PROVIDER = "ANILIST";
    /** Langue gardée pour l'instant : doubleurs japonais (VOSTFR). Le modèle accepte d'autres langues. */
    static final String LANGUAGE = "ja";
    static final Set<String> SEQUEL_FORMATS = Set.of("TV", "TV_SHORT");

    public sealed interface Step permits Done, Idle, Unavailable {
    }

    public record Done(String what) implements Step {
    }

    /** {@code yielded} : du travail attend, mais les métadonnées passent avant. */
    public record Idle(boolean yielded) implements Step {
    }

    public record Unavailable(String message, Duration retryAfter) implements Step {
    }

    @Inject
    CastRepository repository;
    @Inject
    fr.plexwish.animeserver.common.db.Db db;
    @Inject
    AniListProvider anilist;
    @Inject
    MetadataService metadata;
    @Inject
    MetadataConfig metadataConfig;
    @Inject
    PosterDownloader downloader;
    @Inject
    PosterConfig posterConfig;
    @Inject
    CastConfig config;

    private volatile PosterStore store;
    private volatile String lastUnavailable;
    private volatile Instant pausedUntil;

    /** Images de la distribution : sous-dossier « cast » du dossier des affiches, même mécanisme (§17). */
    public PosterStore store() {
        PosterStore s = store;
        if (s == null) {
            s = PosterStore.at(Path.of(posterConfig.path()).resolve("cast"));
            store = s;
        }
        return s;
    }

    public Optional<String> lastUnavailable() {
        return Optional.ofNullable(lastUnavailable);
    }

    public Optional<Instant> pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(Instant.now()) ? Optional.of(p) : Optional.empty();
    }

    /** Les métadonnées ont-elles du travail ? Si oui, aucune requête AniList pour la distribution. */
    public boolean metadataBusy() throws SQLException {
        return metadataConfig.enabled() && metadata.nextPending().isPresent();
    }

    public Step processNext() throws SQLException {
        return processNext(metadataBusy());
    }

    Step processNext(boolean metadataBusy) throws SQLException {
        Optional<Work> work = nextAnime();
        if (work.isPresent() && !metadataBusy) {
            try {
                String what = fetchAnime(work.get());
                lastUnavailable = null;
                return new Done(what);
            } catch (ProviderUnavailableException e) {
                return unavailable(e);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Distribution : échec pour l'animé %d", work.get().animeId());
                failed(work.get(), e.toString());
                return new Done("ERROR");
            }
        }
        Optional<String> image = store().usable() ? nextImage() : Optional.empty();
        if (image.isPresent()) {
            try {
                return new Done(downloadImage(image.get()));
            } catch (ProviderUnavailableException e) {
                return unavailable(e);
            }
        }
        return new Idle(work.isPresent());
    }

    private Unavailable unavailable(ProviderUnavailableException e) {
        Duration wait = e.retryAfter().orElse(config.unavailablePause());
        lastUnavailable = e.getMessage();
        pausedUntil = Instant.now().plus(wait);
        LOG.infof("Distribution : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
        return new Unavailable(e.getMessage(), wait);
    }

    // --- Récupération d'un animé -------------------------------------------------------------------

    /** Un animé à traiter : sa fiche AniList appariée et le nombre de saisons voulues (suites comprises). */
    record Work(long animeId, String mediaId, int seasons, String title) {
    }

    Optional<Work> nextAnime() throws SQLException {
        return repository.nextAnime(config.maxSeasons(), config.refreshAfter().toSeconds());
    }

    /** Un rôle retenu (personnage + doubleur japonais), avec sa place d'origine. */
    record Role(JsonNode character, JsonNode person, String role, int season, int index, String sourceId) {
        String characterId() {
            return character.path("id").asText();
        }
    }

    String fetchAnime(Work w) throws SQLException, ProviderUnavailableException {
        int id;
        try {
            id = Integer.parseInt(w.mediaId());
        } catch (NumberFormatException e) {
            saveState(w, "FAILED", 0, 0, "identifiant AniList invalide", null);
            return "FAILED";
        }
        int perPage = Math.max(1, Math.min(50, config.maxRoles()));
        Optional<JsonNode> first = anilist.cast(id, perPage);
        if (first.isEmpty()) {
            replaceCast(w.animeId(), List.of());
            saveState(w, "FAILED", 0, 0, "fiche AniList introuvable", null);
            LOG.infof("Distribution : %s → fiche AniList introuvable", w.title());
            return "FAILED";
        }
        if (first.get().path("isAdult").asBoolean(false)) {
            replaceCast(w.animeId(), List.of());
            saveState(w, "EXCLUDED", 0, 0, null, null);
            LOG.infof("Distribution : %s → exclue (fiche réservée aux adultes)", w.title());
            return "EXCLUDED";
        }
        List<JsonNode> seasons = new ArrayList<>(List.of(first.get()));
        Set<Integer> seen = new HashSet<>(Set.of(id));
        String error = null;
        ProviderUnavailableException pause = null;
        // Suites : séries TV seulement (ni film, ni OVA, ni spin-off), jusqu'au nombre de saisons du dossier.
        while (seasons.size() < w.seasons()) {
            Optional<Integer> next = sequel(seasons.get(seasons.size() - 1));
            if (next.isEmpty() || !seen.add(next.get())) {
                break;
            }
            try {
                Optional<JsonNode> m = anilist.cast(next.get(), perPage);
                if (m.isEmpty() || m.get().path("isAdult").asBoolean(false)) {
                    break;
                }
                seasons.add(m.get());
            } catch (ProviderUnavailableException e) {
                // Un échec sur une suite ne bloque pas le reste : on garde ce qui a été lu, la suite sera retentée.
                error = "suite " + next.get() + " : " + e.getMessage();
                pause = e;
                break;
            } catch (RuntimeException e) {
                error = "suite " + next.get() + " : " + e;
                break;
            }
        }
        List<Role> roles = merge(seasons, config.maxRoles());
        replaceCast(w.animeId(), roles);
        String status = roles.isEmpty() ? "NONE" : "OK";
        saveState(w, status, seasons.size(), roles.size(), error, error == null ? null : Duration.ofHours(1));
        LOG.infof("Distribution : %s → %d rôle(s), %d saison(s)%s", w.title(), roles.size(), seasons.size(),
                error == null ? "" : " (incomplet, suite retentée)");
        if (pause != null) {
            throw pause;
        }
        return status;
    }

    /** Suite directe de type série TV, non réservée aux adultes ; la plus ancienne s'il y en a plusieurs. */
    static Optional<Integer> sequel(JsonNode media) {
        JsonNode best = null;
        for (JsonNode e : media.path("relations").path("edges")) {
            JsonNode n = e.path("node");
            if ("SEQUEL".equals(e.path("relationType").asText()) && "ANIME".equals(n.path("type").asText())
                    && SEQUEL_FORMATS.contains(n.path("format").asText()) && !n.path("isAdult").asBoolean(false)) {
                if (best == null || compareStart(n, best) < 0) {
                    best = n;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.path("id").asInt());
    }

    private static int compareStart(JsonNode a, JsonNode b) {
        int ya = a.path("startDate").path("year").asInt(Integer.MAX_VALUE);
        int yb = b.path("startDate").path("year").asInt(Integer.MAX_VALUE);
        return ya != yb ? Integer.compare(ya, yb) : Integer.compare(a.path("id").asInt(), b.path("id").asInt());
    }

    /**
     * Un personnage une seule fois, avec son meilleur rôle (principal avant secondaire) ; figurants écartés.
     * Ordre : principaux puis secondaires, saison puis pertinence ; au plus {@code max} rôles.
     */
    static List<Role> merge(List<JsonNode> seasons, int max) {
        Map<String, Role> byCharacter = new LinkedHashMap<>();
        for (int s = 0; s < seasons.size(); s++) {
            JsonNode media = seasons.get(s);
            int i = 0;
            for (JsonNode edge : media.path("characters").path("edges")) {
                String role = edge.path("role").asText();
                JsonNode character = edge.path("node");
                if (!("MAIN".equals(role) || "SUPPORTING".equals(role)) || !character.path("id").isNumber()
                        || name(character) == null) {
                    continue;
                }
                JsonNode person = null;
                for (JsonNode va : edge.path("voiceActors")) {
                    if ("Japanese".equalsIgnoreCase(va.path("languageV2").asText("Japanese")) && va.path("id").isNumber()
                            && name(va) != null) {
                        person = va;
                        break;
                    }
                }
                Role r = new Role(character, person, role, s, i++, media.path("id").asText());
                Role old = byCharacter.get(r.characterId());
                if (old == null) {
                    byCharacter.put(r.characterId(), r);
                } else if ("SUPPORTING".equals(old.role()) && "MAIN".equals(role)) {
                    byCharacter.put(r.characterId(), new Role(character, person != null ? person : old.person(), role,
                            s, r.index(), r.sourceId()));
                } else if (old.person() == null && person != null) {
                    byCharacter.put(r.characterId(), new Role(old.character(), person, old.role(), old.season(),
                            old.index(), old.sourceId()));
                }
            }
        }
        return byCharacter.values().stream()
                .sorted(Comparator.comparing((Role r) -> "MAIN".equals(r.role()) ? 0 : 1)
                        .thenComparingInt(Role::season).thenComparingInt(Role::index))
                .limit(Math.max(0, max))
                .toList();
    }

    static String name(JsonNode node) {
        String full = text(node.path("name").path("full"));
        return full != null ? full : text(node.path("name").path("native"));
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || n.asText().isBlank() ? null : n.asText().trim();
    }

    /** Remplace la distribution d'un animé (une transaction) ; personnes et personnages partagés entre animés. */
    void replaceCast(long animeId, List<Role> roles) throws SQLException {
        db.runInTransaction(c -> {
            repository.deleteCastOf(c, animeId);
            int position = 0;
            for (Role r : roles) {
                long characterId = upsertCharacter(c, r.character());
                Long personId = r.person() == null ? null : upsertPerson(c, r.person());
                repository.insertRole(c, animeId, characterId, LANGUAGE, personId, r.role(), position++, r.sourceId());
            }
        });
        cleanup();
    }

    /** Personnage : nom seulement (pas d'image : seuls les comédiens ont une photo, décision du 2026-10-05). */
    private long upsertCharacter(Connection c, JsonNode node) throws SQLException {
        return repository.upsertCharacter(c, PROVIDER, node.path("id").asText(), name(node), text(node.path("name").path("native")));
    }

    /** Comédien ; sa photo est mise en file de téléchargement. */
    private long upsertPerson(Connection c, JsonNode node) throws SQLException {
        String image = text(node.path("image").path("large"));
        if (image == null) {
            image = text(node.path("image").path("medium"));
        }
        if (image != null && image.contains("/default.")) {
            image = null; // image générique d'AniList : visuel de remplacement de l'application à la place
        }
        long id = repository.upsertPerson(c, PROVIDER, node.path("id").asText(), name(node),
                text(node.path("name").path("native")), image);
        if (image != null) {
            repository.queueImage(c, image);
        }
        return id;
    }

    /** Ménage : personnes, personnages et photos que plus aucune distribution n'utilise (fichiers compris). */
    void cleanup() throws SQLException {
        deleteIfUnreferenced(repository.deleteUnused());
    }

    private void saveState(Work w, String status, int seasons, int roles, String error, Duration retryIn) throws SQLException {
        repository.saveState(w.animeId(), status, PROVIDER, w.mediaId(), seasons, roles,
                retryIn == null ? null : retryIn.toSeconds(),
                error == null ? null : error.length() > 300 ? error.substring(0, 300) : error);
    }

    /** Erreur imprévue : nouvel essai espacé, abandon (FAILED) après {@code maxAttempts}. */
    private void failed(Work w, String error) throws SQLException {
        repository.saveFailure(w.animeId(), PROVIDER, w.mediaId(), error.length() > 300 ? error.substring(0, 300) : error,
                config.maxAttempts());
    }

    /** Admin : redemander la distribution d'un animé (au prochain passage de la tâche). */
    public boolean requeue(long animeId) throws SQLException {
        if (!repository.hasAniListMatch(animeId)) {
            return false;
        }
        repository.requeue(animeId, PROVIDER);
        return true;
    }

    /** « Effacer toute la distribution » : données et images. */
    public int purge() throws SQLException {
        int n = repository.truncateAll();
        store().sweep(Set.of(), Duration.ZERO);
        return n;
    }

    // --- Images -------------------------------------------------------------------------------------------

    Optional<String> nextImage() throws SQLException {
        return repository.nextImage();
    }

    String downloadImage(String url) throws SQLException, ProviderUnavailableException {
        PosterDownloader.Image image;
        try {
            image = downloader.download(url, config.imageMaxBytes());
        } catch (PosterDownloader.Rejected e) {
            imageFailed(url, e.getMessage(), true);
            return "REJECTED";
        } catch (PosterDownloader.Retry e) {
            imageFailed(url, e.getMessage(), false);
            return "RETRY";
        }
        String relative = PosterStore.relativePath(image.sha256(), image.extension());
        store().write(relative, image.bytes());
        Optional<CastRepository.ImageRow> known = repository.image(url);
        if (known.isEmpty()) {
            return "GONE"; // effacée entre-temps
        }
        String oldPath = known.get().relativePath();
        String publicId = image.sha256().equals(known.get().sha256()) ? known.get().publicId() : null;
        repository.imageDownloaded(url, publicId != null ? publicId : newPublicId(), relative, image.sha256(), image.contentType(),
                image.bytes().length);
        if (oldPath != null && !oldPath.equals(relative)) {
            deleteIfUnreferenced(List.of(oldPath));
        }
        return "DOWNLOADED";
    }

    private void imageFailed(String url, String error, boolean permanent) throws SQLException {
        repository.imageFailed(url, error.length() > 300 ? error.substring(0, 300) : error, permanent, config.maxAttempts());
    }

    private void deleteIfUnreferenced(Collection<String> paths) throws SQLException {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> referenced = referenced();
        for (String p : new LinkedHashSet<>(paths)) {
            if (!referenced.contains(p)) {
                store().delete(p);
            }
        }
    }

    public Set<String> referenced() throws SQLException {
        return repository.referencedImages();
    }

    public int sweep() throws SQLException {
        return store().sweep(referenced(), Duration.ofMinutes(10));
    }

    /** URL d'image pour l'API : fichier local (/api/cast-images/…), sinon l'URL d'origine, sinon null. */
    public Map<String, String> imageUrls(Collection<String> sources) {
        Map<String, String> out = new HashMap<>();
        List<String> wanted = sources.stream().filter(s -> s != null).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        List<String> missing = new ArrayList<>();
        try {
            for (CastRepository.ImageRow r : repository.images(wanted)) {
                String src = r.sourceUrl();
                String rel = r.relativePath();
                if (r.publicId() != null && rel != null) {
                    if (store().existing(rel).isPresent()) {
                        out.put(src, "/api/cast-images/" + r.publicId());
                        continue;
                    }
                    missing.add(src);
                }
            }
            for (String src : wanted) {
                out.putIfAbsent(src, src);
            }
            for (String src : missing) {
                repository.markImageMissing(src);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Fichier servi pour /api/cast-images/{publicId} : jamais un chemin venu du client. */
    public record Served(Path file, String contentType, String sha256) {
    }

    public Optional<Served> served(String publicId) throws SQLException {
        Optional<CastRepository.ImageRow> row = repository.servedImage(publicId);
        if (row.isEmpty() || !List.of("image/jpeg", "image/png", "image/webp").contains(row.get().contentType())) {
            return Optional.empty();
        }
        Optional<Path> file = store().existing(row.get().relativePath());
        if (file.isEmpty()) {
            repository.markImageMissing(row.get().sourceUrl());
            return Optional.empty();
        }
        return Optional.of(new Served(file.get(), row.get().contentType(), row.get().sha256()));
    }

    static String newPublicId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
