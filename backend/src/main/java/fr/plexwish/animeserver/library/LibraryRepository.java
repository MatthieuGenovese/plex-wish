package fr.plexwish.animeserver.library;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lecture de la bibliothèque par les utilisateurs : seuls les épisodes visibles (fichier disponible) comptent.
 * Requêtes JPA (JPQL ou SQL natif) sur l'EntityManager.
 */
@ApplicationScoped
public class LibraryRepository {

    /** Critères de la liste des animés (déjà validés). {@code userId} : requis si {@code watch} est donné. */
    public record AnimeQuery(String sort, String search, Integer yearFrom, Integer yearTo, String watch, Long userId,
                             Boolean browser, String genre, int page, int size) {
    }

    public record AnimeRow(long id, String title, Integer year, long episodeCount, Instant lastAddedAt) {
    }

    public record AnimePage(long total, List<AnimeRow> rows) {
    }

    /** Fiche française TMDB récente ({@code tmdbId} sous forme de texte). */
    public record TmdbFrench(String title, String synopsis, String language, String tmdbType, String tmdbId) {
    }

    public record GenreRow(String genre, long animeCount) {
    }

    public record SeasonRow(Long id, int seasonNumber, long episodeCount) {
    }

    public record EpisodeRow(Long id, int episodeNumber, String title, Integer durationSeconds) {
    }

    public record EpisodeDetailRow(Long id, Long animeId, String animeTitle, Long seasonId, int seasonNumber,
                                   int episodeNumber, String title, String synopsis, Integer durationSeconds,
                                   String container, long fileSize) {
    }

    private static final String VISIBLE = "e.mediaFile.available = true";

    @Inject
    EntityManager em;

    /** Animés visibles filtrés, triés et paginés, avec le total (avant pagination). */
    public AnimePage animes(AnimeQuery query) {
        String order = switch (query.sort()) {
            case "recent" -> "last_added DESC, lower(title), id";
            case "title" -> "lower(title), id";
            case "year" -> "year DESC NULLS LAST, lower(title), id";
            default -> throw new IllegalArgumentException("tri inconnu : " + query.sort());
        };
        String watch = query.watch();
        Boolean browser = query.browser();
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("""
                WITH v AS (
                  SELECT a.id, a.title, a.year, count(*) AS episodes, max(m.first_seen_at) AS last_added""");
        if (watch != null) {
            sql.append(", count(*) FILTER (WHERE p.completed) AS seen,"
                    + " count(*) FILTER (WHERE p.completed OR p.position_seconds > 0) AS started");
        }
        if (browser != null) {
            sql.append(", count(*) FILTER (WHERE mp.status = 'OK' AND mp.browser_playable) AS browser_ok");
        }
        sql.append("""

                  FROM episode e JOIN media_file m ON m.id = e.media_file_id AND m.available
                  JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id""");
        if (watch != null) {
            sql.append(" LEFT JOIN playback_progress p ON p.episode_id = e.id AND p.user_id = :user");
            params.put("user", query.userId());
        }
        if (browser != null) {
            sql.append(" LEFT JOIN media_probe mp ON mp.media_file_id = m.id");
        }
        List<String> where = new ArrayList<>();
        if (query.search() != null) {
            where.add("lower(unaccent(a.title)) LIKE lower(unaccent(:q)) ESCAPE '!'");
            params.put("q", query.search());
        }
        if (query.yearFrom() != null) {
            where.add("a.year >= :yearFrom");
            params.put("yearFrom", query.yearFrom());
        }
        if (query.yearTo() != null) {
            where.add("a.year <= :yearTo");
            params.put("yearTo", query.yearTo());
        }
        if (query.genre() != null) {
            where.add("EXISTS (SELECT 1 FROM anime_genre g WHERE g.anime_id = a.id AND g.genre = :genre)");
            params.put("genre", query.genre());
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" GROUP BY a.id)");
        List<String> having = new ArrayList<>();
        if (watch != null) {
            having.add(switch (watch) {
                case "unseen" -> "started = 0";
                case "inProgress" -> "started > 0 AND seen < episodes";
                default -> "seen = episodes";
            });
        }
        if (browser != null) {
            having.add(browser ? "browser_ok = episodes" : "browser_ok < episodes");
        }
        String filter = having.isEmpty() ? "" : " WHERE " + String.join(" AND ", having);
        var count = em.createNativeQuery(sql + " SELECT count(*) FROM v" + filter);
        var items = em.createNativeQuery(sql + " SELECT id, title, year, episodes, last_added FROM v" + filter
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset");
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            items.setParameter(k, v);
        });
        items.setParameter("limit", query.size()).setParameter("offset", (long) query.page() * query.size());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = items.getResultList();
        List<AnimeRow> list = rows.stream().map(r -> new AnimeRow(((Number) r[0]).longValue(), (String) r[1],
                r[2] == null ? null : ((Number) r[2]).intValue(), ((Number) r[3]).longValue(), toInstant(r[4]))).toList();
        return new AnimePage(((Number) count.getSingleResult()).longValue(), list);
    }

    static Instant toInstant(Object v) {
        return switch (v) {
            case null -> null;
            case Instant i -> i;
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("date inattendue : " + v.getClass());
        };
    }

    /** L'animé, ou {@code null}. */
    public Anime findAnime(long id) {
        return em.find(Anime.class, id);
    }

    /** Titre et synopsis français TMDB, s'ils ont moins de {@code maxAgeSeconds}. */
    public Optional<TmdbFrench> tmdbFrench(long animeId, long maxAgeSeconds) {
        return em.createNativeQuery("""
                        SELECT title, synopsis, language, tmdb_type, tmdb_id FROM anime_tmdb
                        WHERE anime_id = ?1 AND fetched_at > now() - make_interval(secs => ?2)""")
                .setParameter(1, animeId).setParameter(2, maxAgeSeconds)
                .getResultStream().findFirst()
                .map(o -> {
                    Object[] r = (Object[]) o;
                    return new TmdbFrench((String) r[0], (String) r[1], (String) r[2], (String) r[3], String.valueOf(r[4]));
                });
    }

    /** Genres AniList d'un animé (valeurs brutes). */
    public List<String> genresOf(long animeId) {
        @SuppressWarnings("unchecked")
        List<String> list = em.createNativeQuery("SELECT genre FROM anime_genre WHERE anime_id = ?1 ORDER BY genre")
                .setParameter(1, animeId).getResultList();
        return list;
    }

    /** Genres présents dans la bibliothèque et nombre d'animés visibles qui les ont. */
    public List<GenreRow> genreCounts() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT g.genre, count(DISTINCT g.anime_id) FROM anime_genre g
                WHERE EXISTS (SELECT 1 FROM season s JOIN episode e ON e.season_id = s.id
                              JOIN media_file m ON m.id = e.media_file_id AND m.available WHERE s.anime_id = g.anime_id)
                GROUP BY g.genre""").getResultList();
        return rows.stream().map(r -> new GenreRow((String) r[0], ((Number) r[1]).longValue())).toList();
    }

    /** Saisons visibles de l'animé, dans l'ordre 1, 2, 3… puis Spéciaux (saison 0). Vide si l'animé est inconnu. */
    public List<SeasonRow> seasons(long animeId) {
        List<Object[]> rows = em.createQuery("select s.id, s.seasonNumber, count(e) from Episode e join e.season s"
                        + " where s.anime.id = :id and " + VISIBLE
                        + " group by s.id, s.seasonNumber"
                        + " order by case when s.seasonNumber = 0 then 1 else 0 end, s.seasonNumber", Object[].class)
                .setParameter("id", animeId)
                .getResultList();
        return rows.stream().map(r -> new SeasonRow((Long) r[0], (Integer) r[1], (Long) r[2])).toList();
    }

    /** Épisodes visibles de la saison, par numéro. */
    public List<EpisodeRow> episodes(long seasonId) {
        return em.createQuery("select new " + EpisodeRow.class.getName()
                        + "(e.id, e.episodeNumber, e.title, e.durationSeconds) from Episode e"
                        + " where e.season.id = :id and " + VISIBLE + " order by e.episodeNumber", EpisodeRow.class)
                .setParameter("id", seasonId)
                .getResultList();
    }

    /** Résultat de l'analyse (lisible dans un navigateur) des épisodes analysés de la saison. */
    public Map<Long, Boolean> browserPlayable(long seasonId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT e.id, p.browser_playable FROM episode e JOIN media_probe p ON p.media_file_id = e.media_file_id
                        WHERE e.season_id = ?1 AND p.status = 'OK'""")
                .setParameter(1, seasonId).getResultList();
        Map<Long, Boolean> playable = new HashMap<>();
        rows.forEach(r -> playable.put(((Number) r[0]).longValue(), (Boolean) r[1]));
        return playable;
    }

    public Optional<EpisodeDetailRow> episode(long id) {
        return em.createQuery("select new " + EpisodeDetailRow.class.getName()
                        + "(e.id, a.id, a.title, s.id, s.seasonNumber, e.episodeNumber, e.title, e.synopsis,"
                        + " e.durationSeconds, e.mediaFile.container, e.mediaFile.fileSize)"
                        + " from Episode e join e.season s join s.anime a where e.id = :id and " + VISIBLE,
                        EpisodeDetailRow.class)
                .setParameter("id", id)
                .getResultStream().findFirst();
    }
}
