package fr.plexwish.animeserver.library;

import fr.plexwish.animeserver.common.ApiException;
import io.quarkus.security.Authenticated;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
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

    public record AnimeDetail(Long id, String title, String alternativeTitle, String synopsis, String posterUrl,
                              Integer year, List<SeasonDto> seasons) {
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

    /** sort=recent : derniers ajouts d'abord (accueil) ; sort=title : ordre alphabétique (bibliothèque). */
    @GET
    @Path("/anime")
    public List<AnimeSummary> list(@QueryParam("sort") @DefaultValue("title") String sort) {
        String order = switch (sort) {
            case "recent" -> "max(e.mediaFile.firstSeenAt) desc, lower(a.title)";
            case "title" -> "lower(a.title)";
            default -> throw new ApiException(400, "INVALID_SORT", "sort doit valoir 'title' ou 'recent'");
        };
        return em.createQuery("select new " + AnimeSummary.class.getName()
                        + "(a.id, a.title, a.year, a.posterUrl, count(e), max(e.mediaFile.firstSeenAt))"
                        + " from Episode e join e.season s join s.anime a where " + VISIBLE
                        + " group by a.id, a.title, a.year, a.posterUrl order by " + order, AnimeSummary.class)
                .getResultList();
    }

    @GET
    @Path("/anime/{id}")
    public AnimeDetail anime(@PathParam("id") long id) {
        List<SeasonDto> seasons = seasons(id);
        Anime a = Anime.findById(id);
        return new AnimeDetail(a.id, a.title, a.alternativeTitle, a.synopsis, a.posterUrl, a.year, seasons);
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
