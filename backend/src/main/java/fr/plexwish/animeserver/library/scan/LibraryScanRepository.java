package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Écritures et lectures du scan de la bibliothèque (media_file, anime, season, episode, scan_issue). Toutes les
 * méthodes s'exécutent sur la connexion du scan, dans sa transaction ; les écritures se font par lot (une requête
 * pour tout le lot, jamais une par fichier).
 */
@ApplicationScoped
public class LibraryScanRepository {

    /** Épisode connu et son fichier lié ({@code fileId} et {@code filePath} null : aucun). */
    record EpisodeLink(long id, long seasonId, int episodeNumber, Long fileId, String filePath) {
    }

    /** Fichier vidéo vu par le scan. */
    record MediaFileRow(String path, String fileName, long size, OffsetDateTime modified, String container, String kind) {
    }

    /** Fichier écrit : {@code inserted} vrai s'il est nouveau. */
    record UpsertedFile(long id, String path, boolean inserted) {
    }

    record SeasonRow(long id, long animeId, int seasonNumber) {
    }

    record NewEpisode(long seasonId, int episodeNumber, Long fileId) {
    }

    record InsertedEpisode(long id, long seasonId, int episodeNumber) {
    }

    record MissingFile(long id, String path) {
    }

    // --- Chargement des caches ---------------------------------------------------------------------

    Map<String, LibraryScanner.ManualOverride> overrides(Connection c) throws SQLException {
        Map<String, LibraryScanner.ManualOverride> map = new HashMap<>();
        Jdbc.list(c, "SELECT relative_path, action, anime_title, season_number, episode_number FROM media_file_override",
                rs -> map.put(rs.getString(1), new LibraryScanner.ManualOverride(rs.getString(2), rs.getString(3),
                        (Integer) rs.getObject(4), (Integer) rs.getObject(5))));
        return map;
    }

    Set<String> availablePaths(Connection c) throws SQLException {
        return new HashSet<>(Jdbc.list(c, "SELECT relative_path FROM media_file WHERE available", rs -> rs.getString(1)));
    }

    /** Titre normalisé → id de l'animé. */
    Map<String, Long> animeIds(Connection c) throws SQLException {
        Map<String, Long> map = new HashMap<>();
        Jdbc.list(c, "SELECT id, normalized_title FROM anime", rs -> map.put(rs.getString(2), rs.getLong(1)));
        return map;
    }

    List<SeasonRow> seasons(Connection c) throws SQLException {
        return Jdbc.list(c, "SELECT id, anime_id, season_number FROM season",
                rs -> new SeasonRow(rs.getLong(1), rs.getLong(2), rs.getInt(3)));
    }

    List<EpisodeLink> episodes(Connection c) throws SQLException {
        return Jdbc.list(c, """
                SELECT e.id, e.season_id, e.episode_number, e.media_file_id, m.relative_path
                FROM episode e LEFT JOIN media_file m ON m.id = e.media_file_id""",
                rs -> new EpisodeLink(rs.getLong(1), rs.getLong(2), rs.getInt(3), (Long) rs.getObject(4), rs.getString(5)));
    }

    // --- Écritures par lot -------------------------------------------------------------------------

    /** Insertion des nouveaux fichiers, mise à jour des connus (réapparus compris), vus à {@code scanTime}. */
    List<UpsertedFile> upsertMediaFiles(Connection c, List<MediaFileRow> files, OffsetDateTime scanTime) throws SQLException {
        List<Object> params = new ArrayList<>(files.size() * 8);
        for (MediaFileRow f : files) {
            params.add(f.path());
            params.add(f.fileName());
            params.add(f.size());
            params.add(f.modified());
            params.add(f.container());
            params.add(f.kind());
            params.add(scanTime);
            params.add(scanTime);
        }
        String sql = "INSERT INTO media_file (relative_path, file_name, file_size, last_modified, container, kind, "
                + "first_seen_at, last_seen_at) VALUES "
                + Jdbc.values(files.size(), "?", "?", "?", "?", "?", "?", "?", "?")
                + " ON CONFLICT (relative_path) DO UPDATE SET file_name = EXCLUDED.file_name, "
                + "file_size = EXCLUDED.file_size, last_modified = EXCLUDED.last_modified, container = EXCLUDED.container, "
                + "kind = EXCLUDED.kind, available = TRUE, missing_since = NULL, last_seen_at = EXCLUDED.last_seen_at "
                + "RETURNING id, relative_path, (xmax = 0) AS inserted";
        return Jdbc.list(c, sql, rs -> new UpsertedFile(rs.getLong(1), rs.getString(2), rs.getBoolean(3)), params.toArray());
    }

    /** Crée les animés (titre normalisé → titre affiché) ; renvoie titre normalisé → id. */
    Map<String, Long> insertAnimes(Connection c, Map<String, String> titlesByNormalized) throws SQLException {
        List<Object> params = new ArrayList<>();
        titlesByNormalized.forEach((norm, title) -> {
            params.add(title);
            params.add(norm);
        });
        String sql = "INSERT INTO anime (title, normalized_title) VALUES " + Jdbc.values(titlesByNormalized.size(), "?", "?")
                + " ON CONFLICT (normalized_title) DO UPDATE SET normalized_title = EXCLUDED.normalized_title"
                + " RETURNING id, normalized_title";
        Map<String, Long> ids = new HashMap<>();
        Jdbc.list(c, sql, rs -> ids.put(rs.getString(2), rs.getLong(1)), params.toArray());
        return ids;
    }

    /** Crée les saisons (animé, numéro) ; renvoie les saisons écrites. */
    List<SeasonRow> insertSeasons(Connection c, List<SeasonRow> seasons) throws SQLException {
        List<Object> params = new ArrayList<>();
        for (SeasonRow s : seasons) {
            params.add(s.animeId());
            params.add(s.seasonNumber());
        }
        String sql = "INSERT INTO season (anime_id, season_number) VALUES " + Jdbc.values(seasons.size(), "?", "?")
                + " ON CONFLICT (anime_id, season_number) DO UPDATE SET season_number = EXCLUDED.season_number"
                + " RETURNING id, anime_id, season_number";
        return Jdbc.list(c, sql, rs -> new SeasonRow(rs.getLong(1), rs.getLong(2), rs.getInt(3)), params.toArray());
    }

    List<InsertedEpisode> insertEpisodes(Connection c, List<NewEpisode> episodes) throws SQLException {
        List<Object> params = new ArrayList<>();
        for (NewEpisode e : episodes) {
            params.add(e.seasonId());
            params.add(e.episodeNumber());
            params.add(e.fileId());
        }
        String sql = "INSERT INTO episode (season_id, episode_number, media_file_id) VALUES "
                + Jdbc.values(episodes.size(), "?", "?", "?") + " RETURNING id, season_id, episode_number";
        return Jdbc.list(c, sql, rs -> new InsertedEpisode(rs.getLong(1), rs.getLong(2), rs.getInt(3)), params.toArray());
    }

    /** Détache ces épisodes de leur fichier. */
    void unlinkEpisodes(Connection c, List<Long> episodeIds) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE episode SET media_file_id = NULL WHERE id = ANY (?)")) {
            st.setArray(1, c.createArrayOf("bigint", episodeIds.toArray()));
            st.executeUpdate();
        }
    }

    /** Lie chaque épisode (clé) à son nouveau fichier (valeur), en une requête. */
    void relinkEpisodes(Connection c, Map<Long, Long> fileByEpisode) throws SQLException {
        List<Object> params = new ArrayList<>();
        fileByEpisode.forEach((episodeId, fileId) -> {
            params.add(episodeId);
            params.add(fileId);
        });
        String sql = "UPDATE episode e SET media_file_id = v.file_id FROM (VALUES "
                + Jdbc.values(fileByEpisode.size(), "?::bigint", "?::bigint") + ") AS v(id, file_id) WHERE e.id = v.id";
        Jdbc.update(c, sql, params.toArray());
    }

    /** Fichiers disponibles non vus depuis {@code scanTime} : indisponibles (jamais supprimés). */
    List<MissingFile> markMissing(Connection c, OffsetDateTime scanTime) throws SQLException {
        return Jdbc.list(c, """
                UPDATE media_file SET available = FALSE, missing_since = now()
                WHERE available AND last_seen_at < ? RETURNING id, relative_path""",
                rs -> new MissingFile(rs.getLong(1), rs.getString(2)), scanTime);
    }

    /** Problèmes du scan, par paquets de 1000. */
    void insertIssues(Connection c, long runId, List<LibraryScanner.Issue> issues) throws SQLException {
        for (int i = 0; i < issues.size(); i += 1000) {
            List<LibraryScanner.Issue> chunk = issues.subList(i, Math.min(i + 1000, issues.size()));
            List<Object> params = new ArrayList<>(chunk.size() * 11);
            for (LibraryScanner.Issue issue : chunk) {
                params.add(runId);
                params.add(issue.fileId());
                params.add(issue.path());
                params.add(issue.category());
                params.add(issue.animeTitle());
                params.add(issue.detail());
                params.add(issue.season());
                params.add(issue.episode());
                params.add(issue.keptPath());
                params.add(issue.seasonSource());
                params.add(issue.keptSeasonSource());
            }
            String sql = "INSERT INTO scan_issue (scan_run_id, media_file_id, relative_path, category, anime_title, detail, "
                    + "season_number, episode_number, kept_relative_path, season_source, kept_season_source) VALUES "
                    + Jdbc.values(chunk.size(), "?", "?::bigint", "?", "?", "?", "?", "?::integer", "?::integer", "?", "?", "?");
            Jdbc.update(c, sql, params.toArray());
        }
    }

    /** Animés qui ont au moins un épisode disponible. */
    int countVisibleAnime(Connection c) throws SQLException {
        return Jdbc.one(c, """
                SELECT count(DISTINCT s.anime_id) FROM episode e
                JOIN season s ON s.id = e.season_id JOIN media_file m ON m.id = e.media_file_id
                WHERE m.available""", rs -> rs.getInt(1)).orElseThrow();
    }
}
