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
                              String tmdbUrl) {
    }

    public record EpisodeSummary(Long id, int episodeNumber, String title, Integer durationSeconds) {
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

    public record AnimePage(long total, int page, int size, List<AnimeSummary> items) {
    }

    /**
     * Animés visibles, paginés. {@code sort=title} : ordre alphabétique (bibliothèque) ; {@code sort=recent} :
     * derniers ajouts d'abord (accueil). {@code q} : recherche dans le titre, sans tenir compte de la casse
     * ni des accents (« chunibyo » trouve « Chûnibyô »).
     */
    @GET
    @Path("/anime")
    public AnimePage list(@QueryParam("sort") @DefaultValue("title") String sort,
                          @QueryParam("q") String q,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("60") @Min(1) @Max(200) int size) {
        String order = switch (sort) {
            case "recent" -> "max(e.mediaFile.firstSeenAt) desc, lower(a.title), a.id";
            case "title" -> "lower(a.title), a.id";
            default -> throw new ApiException(400, "INVALID_SORT", "sort doit valoir 'title' ou 'recent'");
        };
        String search = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim()) + "%";
        String where = " where " + VISIBLE + (search == null ? ""
                : " and lower(function('unaccent', a.title)) like lower(function('unaccent', :q)) escape '!'");
        var count = em.createQuery("select count(distinct a.id) from Episode e join e.season s join s.anime a" + where, Long.class);
        var items = em.createQuery("select new " + AnimeSummary.class.getName()
                        + "(a.id, a.title, a.year, a.posterUrl, count(e), max(e.mediaFile.firstSeenAt))"
                        + " from Episode e join e.season s join s.anime a" + where
                        + " group by a.id, a.title, a.year, a.posterUrl order by " + order, AnimeSummary.class);
        if (search != null) {
            count.setParameter("q", search);
            items.setParameter("q", search);
        }
        List<AnimeSummary> list = items.setFirstResult(page * size).setMaxResults(size).getResultList();
        // Affiche : fichier sur le NAS, sinon URL distante (TMDB, puis AniList), sinon null (ARCHITECTURE §17).
        var urls = posters.urls(list.stream().map(AnimeSummary::id).toList());
        list = list.stream().map(x -> {
            var u = urls.get(x.id());
            return new AnimeSummary(x.id(), x.title(), x.year(), u == null ? null : u.small(), x.episodeCount(), x.lastAddedAt());
        }).toList();
        return new AnimePage(count.getSingleResult(), page, size, list);
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
        return new AnimeDetail(a.id, a.title, a.alternativeTitle, frSynopsis != null ? frSynopsis : a.synopsis,
                frSynopsis != null ? (String) tmdb[2] : a.synopsisLanguage, poster == null ? null : poster.small(),
                poster == null ? null : poster.large(), a.year, anilist,
                a.metadataUrl, seasons, frSynopsis != null ? "TMDB" : a.synopsis != null ? anilist : null, frTitle,
                frSynopsis != null || frTitle != null ? tmdbUrl : null);
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
        return list;
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
