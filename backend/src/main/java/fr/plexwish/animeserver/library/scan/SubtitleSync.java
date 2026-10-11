package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.library.parse.SubtitleNames;
import fr.plexwish.animeserver.library.subs.ExternalSubtitleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Partie « sous-titres externes » du scan (1.3.0, ARCHITECTURE §7.8), après les vidéos : chaque {@code .ass} / {@code .ssa}
 * vu est associé à un épisode (correction de l'admin, sinon {@link ExternalSubtitleService#link}), sa fin lue s'il a
 * changé ; ceux qui ont disparu sont marqués indisponibles. Rapport : sous-titres sans épisode, et sous-titres qui
 * dépassent nettement la durée de leur épisode (mauvais épisode ou mauvaise numérotation, le plus souvent).
 */
@ApplicationScoped
class SubtitleSync {

    /** Marge du contrôle de durée : une minute, ou 5 % de l'épisode si c'est plus. */
    static final double DURATION_MARGIN_SECONDS = 60;
    static final double DURATION_MARGIN_RATIO = 0.05;

    @Inject
    ExternalSubtitleService subtitles;

    private record Known(long size, OffsetDateTime modified, Long cueSize, OffsetDateTime cueModified, BigDecimal lastCue) {
    }

    private record Manual(Long episodeId) {
    }

    List<LibraryScanner.Issue> sync(Connection c, Path root, List<LibraryScanner.Found> found, OffsetDateTime scanTime,
                                    ScanStats stats) throws SQLException {
        List<LibraryScanner.Issue> issues = new ArrayList<>();
        Map<String, Known> known = new HashMap<>();
        Map<String, Manual> overrides = new HashMap<>();
        Map<String, Long> sameName = new HashMap<>();
        Map<String, Long> episodes = new HashMap<>();
        Map<String, Integer> lastEpisodes = new HashMap<>();
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT relative_path, file_size, last_modified, cue_size, cue_modified, last_cue_seconds FROM external_subtitle""")) {
                while (rs.next()) {
                    known.put(rs.getString(1), new Known(rs.getLong(2), rs.getObject(3, OffsetDateTime.class),
                            (Long) rs.getObject(4), rs.getObject(5, OffsetDateTime.class), rs.getBigDecimal(6)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT relative_path, episode_id FROM external_subtitle_override")) {
                while (rs.next()) {
                    overrides.put(rs.getString(1), new Manual((Long) rs.getObject(2)));
                }
            }
            if (!found.isEmpty()) {
                try (ResultSet rs = st.executeQuery("""
                        SELECT m.relative_path, e.id FROM media_file m JOIN episode e ON e.media_file_id = m.id WHERE m.available""")) {
                    while (rs.next()) {
                        String path = rs.getString(1);
                        int slash = path.lastIndexOf('/');
                        String dir = slash < 0 ? "" : path.substring(0, slash);
                        sameName.put(dir + '\0' + SubtitleNames.videoStem(path.substring(slash + 1)), rs.getLong(2));
                    }
                }
                try (ResultSet rs = st.executeQuery("""
                        SELECT a.normalized_title, s.season_number, e.episode_number, e.id
                        FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id""")) {
                    while (rs.next()) {
                        episodes.put(rs.getString(1) + '|' + rs.getInt(2) + '|' + rs.getInt(3), rs.getLong(4));
                        lastEpisodes.merge(rs.getString(1) + '|' + rs.getInt(2), rs.getInt(3), Math::max);
                    }
                }
            }
        }
        ExternalSubtitleService.Lookup lookup = new ExternalSubtitleService.Lookup() {
            @Override
            public Long sameName(String dir, String stem) {
                return sameName.get(dir + '\0' + stem);
            }

            @Override
            public Long episode(String normalizedTitle, int season, int episode) {
                return episodes.get(normalizedTitle + '|' + season + '|' + episode);
            }

            @Override
            public Integer lastEpisode(String normalizedTitle, int season) {
                return lastEpisodes.get(normalizedTitle + '|' + season);
            }
        };

        List<Object> params = new ArrayList<>();
        int rows = 0;
        for (LibraryScanner.Found f : found) {
            stats.externalSubtitles++;
            Manual o = overrides.get(f.path());
            ExternalSubtitleService.Link link;
            if (o != null) {
                link = new ExternalSubtitleService.Link(o.episodeId(), o.episodeId() == null ? null : "MANUAL", null, null);
            } else {
                link = subtitles.link(f.path(), lookup);
            }
            if (link.episodeId() != null) {
                stats.subtitlesLinked++;
            } else if (o == null) {
                stats.subtitlesUnmatched++;
                String detail = link.season() != null
                        ? "saison " + link.season() + ", épisode " + link.episode() + " : épisode absent de la bibliothèque"
                        : "aucune vidéo du même nom dans le dossier, aucun numéro d'épisode reconnu";
                issues.add(new LibraryScanner.Issue(null, f.path(), "SUBTITLE_UNMATCHED", LibraryScanner.topFolder(f.path()),
                        detail, link.season(), link.episode(), null, null, null));
            }
            Known k = known.get(f.path());
            BigDecimal lastCue;
            if (k != null && Objects.equals(k.cueSize(), f.size()) && Objects.equals(k.cueModified(), f.modified())) {
                lastCue = k.lastCue();
            } else {
                OptionalDouble d = ExternalSubtitleService.lastCue(root.resolve(f.path()));
                lastCue = d.isPresent() ? BigDecimal.valueOf(d.getAsDouble()).setScale(3, RoundingMode.HALF_UP) : null;
            }
            String name = f.fileName();
            params.add(f.path());
            params.add(name);
            params.add(f.size());
            params.add(f.modified());
            params.add(scanTime);
            params.add(link.episodeId());
            params.add(link.match());
            params.add(SubtitleNames.forced(name));
            params.add(SubtitleNames.language(name).orElse(null));
            params.add(lastCue);
            params.add(f.size());
            params.add(f.modified());
            if (++rows == 500) {
                upsert(c, rows, params);
                params.clear();
                rows = 0;
            }
        }
        if (rows > 0) {
            upsert(c, rows, params);
        }
        try (PreparedStatement st = c.prepareStatement(
                "UPDATE external_subtitle SET available = FALSE WHERE available AND last_seen_at < ?")) {
            st.setObject(1, scanTime);
            st.executeUpdate();
        }
        issues.addAll(durationIssues(c, stats));
        return issues;
    }

    private static void upsert(Connection c, int rows, List<Object> params) throws SQLException {
        String sql = "INSERT INTO external_subtitle (relative_path, file_name, file_size, last_modified, last_seen_at, episode_id,"
                + " match, forced, language, last_cue_seconds, cue_size, cue_modified, available) VALUES "
                + Sql.values(rows, "?", "?", "?", "?", "?", "?::bigint", "?", "?", "?", "?::numeric", "?", "?", "TRUE")
                + " ON CONFLICT (relative_path) DO UPDATE SET file_name = EXCLUDED.file_name, file_size = EXCLUDED.file_size,"
                + " last_modified = EXCLUDED.last_modified, last_seen_at = EXCLUDED.last_seen_at, episode_id = EXCLUDED.episode_id,"
                + " match = EXCLUDED.match, forced = EXCLUDED.forced, language = EXCLUDED.language,"
                + " last_cue_seconds = EXCLUDED.last_cue_seconds, cue_size = EXCLUDED.cue_size, cue_modified = EXCLUDED.cue_modified,"
                + " available = TRUE";
        try (PreparedStatement st = Sql.prepare(c, sql, params)) {
            st.executeUpdate();
        }
    }

    /** Sous-titres dont le dernier texte arrive nettement après la fin de la vidéo (durée connue par l'analyse ffprobe). */
    private static List<LibraryScanner.Issue> durationIssues(Connection c, ScanStats stats) throws SQLException {
        List<LibraryScanner.Issue> issues = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT x.relative_path, x.last_cue_seconds, p.duration_seconds, a.title, s.season_number, e.episode_number
                FROM external_subtitle x JOIN episode e ON e.id = x.episode_id JOIN season s ON s.id = e.season_id
                JOIN anime a ON a.id = s.anime_id JOIN media_probe p ON p.media_file_id = e.media_file_id
                WHERE x.available AND x.last_cue_seconds IS NOT NULL AND p.duration_seconds IS NOT NULL
                  AND x.last_cue_seconds > p.duration_seconds + greatest(?, p.duration_seconds * ?)
                ORDER BY x.relative_path""")) {
            st.setDouble(1, DURATION_MARGIN_SECONDS);
            st.setDouble(2, DURATION_MARGIN_RATIO);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    stats.subtitleDurationIssues++;
                    issues.add(new LibraryScanner.Issue(null, rs.getString(1), "SUBTITLE_DURATION", rs.getString(4),
                            "dernier sous-titre à " + clock(rs.getDouble(2)) + ", vidéo de " + clock(rs.getDouble(3))
                                    + " : mauvais épisode ?", rs.getInt(5), rs.getInt(6), null, null, null));
                }
            }
        }
        return issues;
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}
