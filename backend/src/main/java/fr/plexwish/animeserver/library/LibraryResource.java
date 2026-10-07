package fr.plexwish.animeserver.library;

import fr.plexwish.animeserver.common.ApiException;
import io.quarkus.security.Authenticated;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * Lecture de la bibliothèque (utilisateurs connectés). Seuls les épisodes dont le fichier est disponible
 * sont visibles ; un animé sans épisode visible n'existe pas pour l'API. Aucun chemin de fichier n'est renvoyé.
 */
@Path("/api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class LibraryResource {

    public record AnimeSummary(Long id, String title, Integer year, String posterUrl, long episodeCount, Instant lastAddedAt) {
    }

    public record SeasonDto(Long id, int seasonNumber, String label, long episodeCount) {
    }

    /**
     * {@code metadataSource} : nom du fournisseur de la fiche (« AniList »), {@code synopsisLanguage} : langue du
     * synopsis (« en »), {@code metadataUrl} : page de la fiche chez le fournisseur. Tout est null sans fiche.
     */
    public record AnimeDetail(Long id, String title, String alternativeTitle, String synopsis, String synopsisLanguage,
                              String posterUrl, String posterLargeUrl, Integer year, String metadataSource,
                              String metadataUrl, List<SeasonDto> seasons, String synopsisSource, String frenchTitle,
                              String tmdbUrl, Resume resume) {
    }

    /**
     * Épisode proposé par le bouton principal de la fiche (ARCHITECTURE §24.3), pour l'utilisateur connecté :
     * {@code kind} = {@code RESUME} (reprendre à {@code positionSeconds}), {@code NEXT} (épisode suivant),
     * {@code START} (jamais regardé : premier épisode) ou {@code REWATCH} (tout vu : premier épisode).
     * {@code durationSeconds} = 0 si inconnue.
     */
    public record Resume(String kind, long episodeId, long seasonId, int seasonNumber, int episodeNumber, String episodeTitle,
                         int positionSeconds, int durationSeconds) {
    }

    /**
     * {@code browserPlayable} : résultat de l'analyse (§22) — false si un navigateur ne peut pas lire le fichier
     * (format), null si le fichier n'est pas encore analysé. Aucun chemin.
     */
    public record EpisodeSummary(Long id, int episodeNumber, String title, Integer durationSeconds, Boolean browserPlayable) {
        public EpisodeSummary(Long id, int episodeNumber, String title, Integer durationSeconds) {
            this(id, episodeNumber, title, durationSeconds, null);
        }
    }

    public record EpisodeDetail(Long id, Long animeId, String animeTitle, Long seasonId, int seasonNumber,
                                int episodeNumber, String title, String synopsis, Integer durationSeconds,
                                String container, long fileSize) {
    }

    private static final String VISIBLE = "e.mediaFile.available = true";

    @Inject
    EntityManager em;
    @Inject
    fr.plexwish.animeserver.tmdb.TmdbConfig tmdbConfig;
    @Inject
    fr.plexwish.animeserver.poster.PosterService posters;
    @Inject
    org.eclipse.microprofile.jwt.JsonWebToken jwt;
    @Inject
    fr.plexwish.animeserver.progress.UpNextService upNext;

    public record AnimePage(long total, int page, int size, List<AnimeSummary> items) {
    }

    /** Valeurs de {@code watch} (progression de l'utilisateur connecté). */
    static final java.util.Set<String> WATCH = java.util.Set.of("unseen", "inProgress", "seen");

    /**
     * Animés visibles, paginés. {@code sort=title} : ordre alphabétique (bibliothèque) ; {@code sort=recent} :
     * derniers ajouts d'abord (accueil). {@code q} : recherche dans le titre, sans tenir compte de la casse
     * ni des accents (« chunibyo » trouve « Chûnibyô »).
     * Filtres (ARCHITECTURE §24.2), cumulables : {@code yearFrom} / {@code yearTo} (animés sans année exclus) ;
     * {@code watch} = {@code unseen} (aucun épisode commencé), {@code inProgress} (commencé, pas tout vu),
     * {@code seen} (tous les épisodes vus), d'après la progression de l'utilisateur ; {@code browser=true} : tous les
     * épisodes analysés et lisibles dans un navigateur, {@code browser=false} : au moins un qui ne l'est pas (ou pas
     * encore analysé).
     */
    @GET
    @Path("/anime")
    public AnimePage list(@QueryParam("sort") @DefaultValue("title") String sort,
                          @QueryParam("q") String q,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("60") @Min(1) @Max(200) int size,
                          @QueryParam("yearFrom") @Min(1900) @Max(2100) Integer yearFrom,
                          @QueryParam("yearTo") @Min(1900) @Max(2100) Integer yearTo,
                          @QueryParam("watch") String watch,
                          @QueryParam("browser") Boolean browser) {
        String order = switch (sort) {
            case "recent" -> "last_added DESC, lower(title), id";
            case "title" -> "lower(title), id";
            default -> throw new ApiException(400, "INVALID_SORT", "sort doit valoir 'title' ou 'recent'");
        };
        if (watch != null && !WATCH.contains(watch)) {
            throw new ApiException(400, "INVALID_FILTER", "watch doit valoir 'unseen', 'inProgress' ou 'seen'");
        }
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
            throw new ApiException(400, "INVALID_FILTER", "yearFrom doit être inférieur ou égal à yearTo");
        }
        String search = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim()) + "%";
        java.util.Map<String, Object> params = new java.util.HashMap<>();
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
            params.put("user", Long.parseLong(jwt.getSubject()));
        }
        if (browser != null) {
            sql.append(" LEFT JOIN media_probe mp ON mp.media_file_id = m.id");
        }
        List<String> where = new java.util.ArrayList<>();
        if (search != null) {
            where.add("lower(unaccent(a.title)) LIKE lower(unaccent(:q)) ESCAPE '!'");
            params.put("q", search);
        }
        if (yearFrom != null) {
            where.add("a.year >= :yearFrom");
            params.put("yearFrom", yearFrom);
        }
        if (yearTo != null) {
            where.add("a.year <= :yearTo");
            params.put("yearTo", yearTo);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" GROUP BY a.id)");
        List<String> having = new java.util.ArrayList<>();
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
        items.setParameter("limit", size).setParameter("offset", (long) page * size);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = items.getResultList();
        // Affiche : fichier sur le NAS, sinon URL distante (TMDB, puis AniList), sinon null (ARCHITECTURE §17).
        var urls = posters.urls(rows.stream().map(r -> ((Number) r[0]).longValue()).toList());
        List<AnimeSummary> list = rows.stream().map(r -> {
            long id = ((Number) r[0]).longValue();
            var u = urls.get(id);
            return new AnimeSummary(id, (String) r[1], r[2] == null ? null : ((Number) r[2]).intValue(),
                    u == null ? null : u.small(), ((Number) r[3]).longValue(), toInstant(r[4]));
        }).toList();
        return new AnimePage(((Number) count.getSingleResult()).longValue(), page, size, list);
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

    /** Échappe les jokers de LIKE (caractère d'échappement : « ! »). */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    @GET
    @Path("/anime/{id}")
    public AnimeDetail anime(@PathParam("id") long id) {
        List<SeasonDto> seasons = seasons(id);
        Anime a = Anime.findById(id);
        // Synopsis : français de TMDB s'il existe (et a moins de 6 mois), sinon anglais d'AniList (ARCHITECTURE §16).
        Object[] tmdb = (Object[]) em.createNativeQuery("""
                        SELECT title, synopsis, language, tmdb_type, tmdb_id FROM anime_tmdb
                        WHERE anime_id = ?1 AND fetched_at > now() - make_interval(secs => ?2)""")
                .setParameter(1, id).setParameter(2, tmdbConfig.maxAge().toSeconds())
                .getResultStream().findFirst().orElse(null);
        String frSynopsis = tmdb == null ? null : (String) tmdb[1];
        String frTitle = tmdb == null ? null : (String) tmdb[0];
        String tmdbUrl = tmdb == null || tmdb[3] == null ? null : "https://www.themoviedb.org/" + tmdb[3] + "/" + tmdb[4];
        String anilist = "ANILIST".equals(a.metadataProvider) ? "AniList" : a.metadataProvider;
        var poster = posters.urls(List.of(id)).get(id);
        Resume resume;
        try {
            resume = upNext.forAnime(Long.parseLong(jwt.getSubject()), id)
                    .map(t -> new Resume(t.kind().name(), t.episodeId(), t.seasonId(), t.seasonNumber(), t.episodeNumber(),
                            t.episodeTitle(), t.positionSeconds(), t.durationSeconds()))
                    .orElse(null);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return new AnimeDetail(a.id, a.title, a.alternativeTitle, frSynopsis != null ? frSynopsis : a.synopsis,
                frSynopsis != null ? (String) tmdb[2] : a.synopsisLanguage, poster == null ? null : poster.small(),
                poster == null ? null : poster.large(), a.year, anilist,
                a.metadataUrl, seasons, frSynopsis != null ? "TMDB" : a.synopsis != null ? anilist : null, frTitle,
                frSynopsis != null || frTitle != null ? tmdbUrl : null, resume);
    }

    /** Saisons dans l'ordre 1, 2, 3… puis Spéciaux (saison 0) en dernier. */
    @GET
    @Path("/anime/{id}/seasons")
    public List<SeasonDto> seasons(@PathParam("id") long id) {
        List<Object[]> rows = em.createQuery("select s.id, s.seasonNumber, count(e) from Episode e join e.season s"
                        + " where s.anime.id = :id and " + VISIBLE
                        + " group by s.id, s.seasonNumber"
                        + " order by case when s.seasonNumber = 0 then 1 else 0 end, s.seasonNumber", Object[].class)
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) {
            throw notFound("ANIME_NOT_FOUND", "Animé introuvable");
        }
        return rows.stream().map(r -> {
            int number = (Integer) r[1];
            return new SeasonDto((Long) r[0], number, number == 0 ? "Spéciaux" : "Saison " + number, (Long) r[2]);
        }).toList();
    }

    @GET
    @Path("/seasons/{id}/episodes")
    public List<EpisodeSummary> episodes(@PathParam("id") long seasonId) {
        List<EpisodeSummary> list = em.createQuery("select new " + EpisodeSummary.class.getName()
                        + "(e.id, e.episodeNumber, e.title, e.durationSeconds) from Episode e"
                        + " where e.season.id = :id and " + VISIBLE + " order by e.episodeNumber", EpisodeSummary.class)
                .setParameter("id", seasonId)
                .getResultList();
        if (list.isEmpty()) {
            throw notFound("SEASON_NOT_FOUND", "Saison introuvable");
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT e.id, p.browser_playable FROM episode e JOIN media_probe p ON p.media_file_id = e.media_file_id
                        WHERE e.season_id = ?1 AND p.status = 'OK'""")
                .setParameter(1, seasonId).getResultList();
        java.util.Map<Long, Boolean> playable = new java.util.HashMap<>();
        rows.forEach(r -> playable.put(((Number) r[0]).longValue(), (Boolean) r[1]));
        return list.stream().map(e -> new EpisodeSummary(e.id(), e.episodeNumber(), e.title(), e.durationSeconds(),
                playable.get(e.id()))).toList();
    }

    @GET
    @Path("/episodes/{id}")
    public EpisodeDetail episode(@PathParam("id") long id) {
        return em.createQuery("select new " + EpisodeDetail.class.getName()
                        + "(e.id, a.id, a.title, s.id, s.seasonNumber, e.episodeNumber, e.title, e.synopsis,"
                        + " e.durationSeconds, e.mediaFile.container, e.mediaFile.fileSize)"
                        + " from Episode e join e.season s join s.anime a where e.id = :id and " + VISIBLE,
                        EpisodeDetail.class)
                .setParameter("id", id)
                .getResultStream().findFirst()
                .orElseThrow(() -> notFound("EPISODE_NOT_FOUND", "Épisode introuvable"));
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }
}
