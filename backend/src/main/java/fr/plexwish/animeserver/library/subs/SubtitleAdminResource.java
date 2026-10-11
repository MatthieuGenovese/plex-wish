package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.scan.Titles;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sous-titres externes dans l'administration (1.3.0, ARCHITECTURE §7.8) : liste (association, contrôle de durée) et
 * correction manuelle (rattacher à un épisode, détacher, revenir à l'automatique), stockée en base et jamais écrasée
 * par un scan. Un sous-titre est toujours désigné par son id en base.
 */
@Path("/api/admin/library/subtitles")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class SubtitleAdminResource {

    static final Set<String> FILTERS = Set.of("ALL", "UNMATCHED", "MANUAL", "DURATION");

    public record EpisodeRef(long id, String animeTitle, int seasonNumber, int episodeNumber) {
    }

    /**
     * {@code match} : SAME_NAME, NAME, MANUAL ou null ; {@code manual} : une correction existe (rattaché ou détaché) ;
     * {@code tooLong} : le dernier sous-titre arrive nettement après la fin de la vidéo.
     */
    public record SubtitleDto(long id, String relativePath, String animeFolder, boolean available, String match, boolean manual,
                              boolean forced, String language, EpisodeRef episode, Double lastCueSeconds,
                              Double videoDurationSeconds, boolean tooLong) {
    }

    public record Summary(long total, long linked, long unmatched, long manual, long tooLong) {
    }

    public record SubtitlePage(Summary summary, long total, int page, int size, List<SubtitleDto> items) {
    }

    /** Épisode visé ; animé : celui du dossier du sous-titre si absent. */
    public record AttachRequest(@Size(max = 300) String animeTitle, @NotNull @Min(0) @Max(99) Integer seasonNumber,
                                @NotNull @Min(0) @Max(9999) Integer episodeNumber) {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    ExternalSubtitleService subtitles;
    @Inject
    JsonWebToken jwt;

    private static final String SELECT = """
            SELECT x.id, x.relative_path, x.available, x.match, (o.relative_path IS NOT NULL), x.forced, x.language,
                   e.id, a.title, s.season_number, e.episode_number, x.last_cue_seconds, p.duration_seconds,
                   (x.last_cue_seconds IS NOT NULL AND p.duration_seconds IS NOT NULL
                    AND x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05))
            FROM external_subtitle x
            LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
            LEFT JOIN episode e ON e.id = x.episode_id
            LEFT JOIN season s ON s.id = e.season_id
            LEFT JOIN anime a ON a.id = s.anime_id
            LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id
            """;

    @GET
    public SubtitlePage list(@QueryParam("filter") @DefaultValue("ALL") String filter,
                             @QueryParam("anime") String anime,
                             @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                             @QueryParam("size") @DefaultValue("100") @Min(1) @Max(500) int size) throws SQLException {
        if (!FILTERS.contains(filter)) {
            throw new ApiException(400, "INVALID_FILTER", "Filtre inconnu : " + String.join(", ", FILTERS));
        }
        String like = anime == null || anime.isBlank() ? null
                : "%" + anime.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " WHERE x.available AND (?::text IS NULL OR x.relative_path ILIKE ?)" + switch (filter) {
            case "UNMATCHED" -> " AND x.episode_id IS NULL AND o.relative_path IS NULL";
            case "MANUAL" -> " AND o.relative_path IS NOT NULL";
            case "DURATION" -> " AND x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05)";
            default -> "";
        };
        try (Connection c = dataSource.getConnection()) {
            Summary summary;
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT count(*), count(x.episode_id), count(*) FILTER (WHERE x.episode_id IS NULL AND o.relative_path IS NULL),
                           count(o.relative_path),
                           count(*) FILTER (WHERE x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05))
                    FROM external_subtitle x
                    LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
                    LEFT JOIN episode e ON e.id = x.episode_id
                    LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id
                    WHERE x.available""");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                summary = new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
            }
            long total;
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT count(*) FROM external_subtitle x
                    LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
                    LEFT JOIN episode e ON e.id = x.episode_id
                    LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id""" + where)) {
                st.setString(1, like);
                st.setString(2, like);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<SubtitleDto> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(SELECT + where + " ORDER BY x.relative_path LIMIT ? OFFSET ?")) {
                st.setString(1, like);
                st.setString(2, like);
                st.setInt(3, size);
                st.setLong(4, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(dto(rs));
                    }
                }
            }
            return new SubtitlePage(summary, total, page, size, items);
        }
    }

    /** Rattache le sous-titre à un épisode (correction manuelle, effet immédiat). */
    @PUT
    @Path("/{id}/episode")
    @Consumes(MediaType.APPLICATION_JSON)
    public SubtitleDto attach(@PathParam("id") long id, @Valid @NotNull AttachRequest request) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String path = pathOf(c, id);
            String title = request.animeTitle() == null || request.animeTitle().isBlank()
                    ? path.substring(0, Math.max(0, path.indexOf('/'))) : request.animeTitle();
            Long episodeId = ExternalSubtitleService.episodeId(c, Titles.normalize(title), request.seasonNumber(),
                    request.episodeNumber());
            if (episodeId == null) {
                throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable : " + title + ", saison "
                        + request.seasonNumber() + ", épisode " + request.episodeNumber());
            }
            save(c, path, episodeId, "MANUAL");
            return get(c, id);
        }
    }

    /** Détache le sous-titre (il ne sera plus proposé ; correction manuelle gardée par les scans). */
    @DELETE
    @Path("/{id}/episode")
    public SubtitleDto detach(@PathParam("id") long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            save(c, pathOf(c, id), null, null);
            return get(c, id);
        }
    }

    /** Retire la correction : association automatique, recalculée tout de suite. */
    @DELETE
    @Path("/{id}/override")
    public SubtitleDto automatic(@PathParam("id") long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String path = pathOf(c, id);
            try (PreparedStatement st = c.prepareStatement("DELETE FROM external_subtitle_override WHERE relative_path = ?")) {
                st.setString(1, path);
                st.executeUpdate();
            }
            ExternalSubtitleService.Link link = subtitles.link(path, subtitles.databaseLookup(c));
            update(c, path, link.episodeId(), link.match());
            return get(c, id);
        }
    }

    private void save(Connection c, String path, Long episodeId, String match) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO external_subtitle_override (relative_path, episode_id, created_by) VALUES (?, ?, ?)
                ON CONFLICT (relative_path) DO UPDATE SET episode_id = EXCLUDED.episode_id, created_by = EXCLUDED.created_by,
                    created_at = now()""")) {
            st.setString(1, path);
            st.setObject(2, episodeId);
            st.setString(3, jwt.getName());
            st.executeUpdate();
        }
        update(c, path, episodeId, match);
    }

    private static void update(Connection c, String path, Long episodeId, String match) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "UPDATE external_subtitle SET episode_id = ?, match = ? WHERE relative_path = ?")) {
            st.setObject(1, episodeId);
            st.setString(2, match);
            st.setString(3, path);
            st.executeUpdate();
        }
    }

    private static String pathOf(Connection c, long id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT relative_path FROM external_subtitle WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "SUBTITLE_NOT_FOUND", "Sous-titre introuvable");
                }
                return rs.getString(1);
            }
        }
    }

    private static SubtitleDto get(Connection c, long id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(SELECT + " WHERE x.id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return dto(rs);
            }
        }
    }

    private static SubtitleDto dto(ResultSet rs) throws SQLException {
        String path = rs.getString(2);
        Long episodeId = (Long) rs.getObject(8);
        EpisodeRef ep = episodeId == null ? null : new EpisodeRef(episodeId, rs.getString(9), rs.getInt(10), rs.getInt(11));
        return new SubtitleDto(rs.getLong(1), path, path.indexOf('/') < 0 ? null : path.substring(0, path.indexOf('/')),
                rs.getBoolean(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7), ep, number(rs, 12),
                number(rs, 13), rs.getBoolean(14));
    }

    private static Double number(ResultSet rs, int col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }
}
