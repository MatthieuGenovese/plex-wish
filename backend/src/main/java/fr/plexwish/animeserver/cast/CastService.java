package fr.plexwish.animeserver.cast;

import com.fasterxml.jackson.databind.JsonNode;
import fr.plexwish.animeserver.metadata.AniListProvider;
import fr.plexwish.animeserver.metadata.MetadataConfig;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.MetadataService;
import fr.plexwish.animeserver.poster.PosterConfig;
import fr.plexwish.animeserver.poster.PosterDownloader;
import fr.plexwish.animeserver.poster.PosterStore;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    AgroalDataSource dataSource;
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

    private static final String WANTED = """
            LEAST(?, GREATEST(1, (SELECT count(*) FROM season s WHERE s.anime_id = a.id AND s.season_number > 0)))""";

    Optional<Work> nextAnime() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT a.id, a.metadata_provider_id, " + WANTED + ", a.title" + """
                      FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id
                     WHERE a.metadata_provider = 'ANILIST' AND a.metadata_provider_id IS NOT NULL
                       AND (st.next_attempt_at IS NULL OR st.next_attempt_at <= now())
                       AND (st.anime_id IS NULL OR st.status = 'PENDING'
                            OR st.source_id IS DISTINCT FROM a.metadata_provider_id
                            OR (st.status IN ('OK', 'NONE') AND st.fetched_at < now() - make_interval(secs => ?))
                            OR (st.status = 'OK' AND st.last_error IS NOT NULL AND st.seasons < """ + WANTED + """
                     ))
                     ORDER BY st.anime_id NULLS FIRST, a.id LIMIT 1""")) {
            st.setInt(1, config.maxSeasons());
            st.setLong(2, config.refreshAfter().toSeconds());
            st.setInt(3, config.maxSeasons());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(new Work(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4))) : Optional.empty();
            }
        }
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
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM anime_cast WHERE anime_id = ?")) {
                    del.setLong(1, animeId);
                    del.executeUpdate();
                }
                int position = 0;
                for (Role r : roles) {
                    long characterId = upsertCharacter(c, r.character());
                    Long personId = r.person() == null ? null : upsertPerson(c, r.person());
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO anime_cast (anime_id, character_id, language, person_id, role, position, source_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                        ins.setLong(1, animeId);
                        ins.setLong(2, characterId);
                        ins.setString(3, LANGUAGE);
                        ins.setObject(4, personId, java.sql.Types.BIGINT);
                        ins.setString(5, r.role());
                        ins.setInt(6, position++);
                        ins.setString(7, r.sourceId());
                        ins.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        cleanup();
    }

    /** Personnage : nom seulement (pas d'image : seuls les comédiens ont une photo, décision du 2026-10-05). */
    private long upsertCharacter(Connection c, JsonNode node) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO cast_character (provider, provider_id, name, native_name, fetched_at) VALUES (?, ?, ?, ?, now())
                ON CONFLICT (provider, provider_id) DO UPDATE SET name = EXCLUDED.name, native_name = EXCLUDED.native_name,
                    fetched_at = now()
                RETURNING id""")) {
            st.setString(1, PROVIDER);
            st.setString(2, node.path("id").asText());
            st.setString(3, name(node));
            st.setString(4, text(node.path("name").path("native")));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
        long id;
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO person (provider, provider_id, name, native_name, image_url, fetched_at) VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (provider, provider_id) DO UPDATE SET name = EXCLUDED.name, native_name = EXCLUDED.native_name,
                    image_url = EXCLUDED.image_url, fetched_at = now()
                RETURNING id""")) {
            st.setString(1, PROVIDER);
            st.setString(2, node.path("id").asText());
            st.setString(3, name(node));
            st.setString(4, text(node.path("name").path("native")));
            st.setString(5, image);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        if (image != null) {
            try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO cast_image (source_url, status) VALUES (?, 'PENDING') ON CONFLICT DO NOTHING")) {
                st.setString(1, image);
                st.executeUpdate();
            }
        }
        return id;
    }

    /** Ménage : personnes, personnages et photos que plus aucune distribution n'utilise (fichiers compris). */
    void cleanup() throws SQLException {
        List<String> paths = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement(
                    "DELETE FROM person p WHERE NOT EXISTS (SELECT 1 FROM anime_cast ac WHERE ac.person_id = p.id)")) {
                st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement(
                    "DELETE FROM cast_character ch WHERE NOT EXISTS (SELECT 1 FROM anime_cast ac WHERE ac.character_id = ch.id)")) {
                st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement("""
                    DELETE FROM cast_image i WHERE NOT EXISTS (SELECT 1 FROM person p WHERE p.image_url = i.source_url)
                    RETURNING relative_path""");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        paths.add(rs.getString(1));
                    }
                }
            }
        }
        deleteIfUnreferenced(paths);
    }

    private void saveState(Work w, String status, int seasons, int roles, String error, Duration retryIn) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO anime_cast_state (anime_id, status, provider, source_id, seasons, roles, fetched_at, attempts,
                                                   next_attempt_at, last_error, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, now(), 0, now() + make_interval(secs => ?), ?, now())
                     ON CONFLICT (anime_id) DO UPDATE SET status = EXCLUDED.status, provider = EXCLUDED.provider,
                         source_id = EXCLUDED.source_id, seasons = EXCLUDED.seasons, roles = EXCLUDED.roles,
                         fetched_at = now(), attempts = 0, next_attempt_at = EXCLUDED.next_attempt_at,
                         last_error = EXCLUDED.last_error, updated_at = now()""")) {
            st.setLong(1, w.animeId());
            st.setString(2, status);
            st.setString(3, PROVIDER);
            st.setString(4, w.mediaId());
            st.setInt(5, seasons);
            st.setInt(6, roles);
            st.setObject(7, retryIn == null ? null : retryIn.toSeconds(), java.sql.Types.BIGINT);
            st.setString(8, error == null ? null : error.length() > 300 ? error.substring(0, 300) : error);
            st.executeUpdate();
        }
    }

    /** Erreur imprévue : nouvel essai espacé, abandon (FAILED) après {@code maxAttempts}. */
    private void failed(Work w, String error) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO anime_cast_state (anime_id, status, provider, source_id, attempts, next_attempt_at, last_error)
                     VALUES (?, 'PENDING', ?, ?, 1, now() + interval '2 minutes', ?)
                     ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_cast_state.attempts + 1,
                         status = CASE WHEN anime_cast_state.attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                         source_id = EXCLUDED.source_id,
                         next_attempt_at = now() + make_interval(mins => power(2, anime_cast_state.attempts + 1)::int),
                         last_error = EXCLUDED.last_error, updated_at = now()""")) {
            st.setLong(1, w.animeId());
            st.setString(2, PROVIDER);
            st.setString(3, w.mediaId());
            st.setString(4, error.length() > 300 ? error.substring(0, 300) : error);
            st.setInt(5, config.maxAttempts());
            st.executeUpdate();
        }
    }

    /** Admin : redemander la distribution d'un animé (au prochain passage de la tâche). */
    public boolean requeue(long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT 1 FROM anime WHERE id = ? AND metadata_provider = 'ANILIST' AND metadata_provider_id IS NOT NULL")) {
                st.setLong(1, animeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO anime_cast_state (anime_id, status, provider) VALUES (?, 'PENDING', ?)
                    ON CONFLICT (anime_id) DO UPDATE SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                        last_error = NULL, updated_at = now()""")) {
                st.setLong(1, animeId);
                st.setString(2, PROVIDER);
                st.executeUpdate();
            }
            // Les photos en échec de ses comédiens sont retentées aussi.
            try (PreparedStatement st = c.prepareStatement("""
                    UPDATE cast_image SET status = 'PENDING', attempts = 0, next_attempt_at = NULL, last_error = NULL
                    WHERE status = 'FAILED' AND source_url IN (
                        SELECT p.image_url FROM anime_cast ac JOIN person p ON p.id = ac.person_id WHERE ac.anime_id = ?)""")) {
                st.setLong(1, animeId);
                st.executeUpdate();
            }
        }
        return true;
    }

    /** « Effacer toute la distribution » : données et images. */
    public int purge() throws SQLException {
        int n;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement count = c.prepareStatement("SELECT count(*) FROM anime_cast");
                 ResultSet rs = count.executeQuery()) {
                rs.next();
                n = rs.getInt(1);
            }
            try (PreparedStatement st = c.prepareStatement(
                    "TRUNCATE anime_cast, anime_cast_state, person, cast_character, cast_image")) {
                st.execute();
            }
            c.commit();
        }
        store().sweep(Set.of(), Duration.ZERO);
        return n;
    }

    // --- Images -------------------------------------------------------------------------------------------

    Optional<String> nextImage() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT source_url FROM cast_image WHERE status = 'PENDING'
                       AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                     ORDER BY attempts, source_url LIMIT 1""");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
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
        String oldPath = null;
        String publicId = null;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT relative_path, sha256, public_id FROM cast_image WHERE source_url = ?")) {
                st.setString(1, url);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        return "GONE"; // effacée entre-temps
                    }
                    oldPath = rs.getString(1);
                    publicId = image.sha256().equals(rs.getString(2)) ? rs.getString(3) : null;
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    UPDATE cast_image SET status = 'OK', public_id = ?, relative_path = ?, sha256 = ?, content_type = ?,
                        bytes = ?, fetched_at = now(), attempts = 0, next_attempt_at = NULL, last_error = NULL
                    WHERE source_url = ?""")) {
                st.setString(1, publicId != null ? publicId : newPublicId());
                st.setString(2, relative);
                st.setString(3, image.sha256());
                st.setString(4, image.contentType());
                st.setLong(5, image.bytes().length);
                st.setString(6, url);
                st.executeUpdate();
            }
        }
        if (oldPath != null && !oldPath.equals(relative)) {
            deleteIfUnreferenced(List.of(oldPath));
        }
        return "DOWNLOADED";
    }

    private void imageFailed(String url, String error, boolean permanent) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE cast_image SET attempts = attempts + 1,
                         status = CASE WHEN relative_path IS NOT NULL THEN 'OK'
                                       WHEN ? OR attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                         next_attempt_at = CASE WHEN ? OR attempts + 1 >= ? THEN NULL
                                                ELSE now() + make_interval(mins => power(2, attempts + 1)::int) END,
                         last_error = ?
                     WHERE source_url = ?""")) {
            st.setBoolean(1, permanent);
            st.setInt(2, config.maxAttempts());
            st.setBoolean(3, permanent);
            st.setInt(4, config.maxAttempts());
            st.setString(5, error.length() > 300 ? error.substring(0, 300) : error);
            st.setString(6, url);
            st.executeUpdate();
        }
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
        Set<String> out = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT DISTINCT relative_path FROM cast_image WHERE relative_path IS NOT NULL");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
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
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT source_url, public_id, relative_path FROM cast_image WHERE source_url = ANY(?)")) {
            Array arr = c.createArrayOf("text", wanted.toArray());
            st.setArray(1, arr);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String src = rs.getString(1);
                    String rel = rs.getString(3);
                    if (rs.getString(2) != null && rel != null) {
                        if (store().existing(rel).isPresent()) {
                            out.put(src, "/api/cast-images/" + rs.getString(2));
                            continue;
                        }
                        missing.add(src);
                    }
                }
            }
            for (String src : wanted) {
                out.putIfAbsent(src, src);
            }
            for (String src : missing) {
                markMissing(c, src);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static void markMissing(Connection c, String source) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                UPDATE cast_image SET status = 'PENDING', public_id = NULL, relative_path = NULL, sha256 = NULL,
                    attempts = 0, next_attempt_at = NULL, last_error = 'fichier local disparu' WHERE source_url = ?""")) {
            st.setString(1, source);
            st.executeUpdate();
        }
    }

    /** Fichier servi pour /api/cast-images/{publicId} : jamais un chemin venu du client. */
    public record Served(Path file, String contentType, String sha256) {
    }

    public Optional<Served> served(String publicId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT source_url, relative_path, content_type, sha256 FROM cast_image WHERE public_id = ? AND relative_path IS NOT NULL")) {
            st.setString(1, publicId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || !List.of("image/jpeg", "image/png", "image/webp").contains(rs.getString(3))) {
                    return Optional.empty();
                }
                Optional<Path> file = store().existing(rs.getString(2));
                if (file.isEmpty()) {
                    markMissing(c, rs.getString(1));
                    return Optional.empty();
                }
                return Optional.of(new Served(file.get(), rs.getString(3), rs.getString(4)));
            }
        }
    }

    static String newPublicId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
