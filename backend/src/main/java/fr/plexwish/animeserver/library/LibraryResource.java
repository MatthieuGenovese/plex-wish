package fr.plexwish.animeserver.library;

import fr.plexwish.animeserver.common.ApiException;
import io.quarkus.security.Authenticated;
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
                              String tmdbUrl, Resume resume, List<GenreDto> genres) {
    }

    /** Genre : valeur AniList (paramètre {@code genre} de la liste) et libellé français (§24.6). */
    public record GenreDto(String genre, String label) {
    }

    /** Genre et nombre d'animés visibles qui l'ont. */
    public record GenreCount(String genre, String label, long animeCount) {
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
    }

    public record EpisodeDetail(Long id, Long animeId, String animeTitle, Long seasonId, int seasonNumber,
                                int episodeNumber, String title, String synopsis, Integer durationSeconds,
                                String container, long fileSize) {
    }

    @Inject
    LibraryRepository library;
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
     * derniers ajouts d'abord (accueil) ; {@code sort=year} : plus récents d'abord, sans année à la
     * fin (ARCHITECTURE §24.4). {@code q} : recherche dans le titre, sans tenir compte de la casse
     * ni des accents (« chunibyo » trouve « Chûnibyô »).
     * Filtres (ARCHITECTURE §24.2), cumulables : {@code yearFrom} / {@code yearTo} (animés sans année exclus) ;
     * {@code watch} = {@code unseen} (aucun épisode commencé), {@code inProgress} (commencé, pas tout vu),
     * {@code seen} (tous les épisodes vus), d'après la progression de l'utilisateur ; {@code browser=true} : tous les
     * épisodes analysés et lisibles dans un navigateur, {@code browser=false} : au moins un qui ne l'est pas (ou pas
     * encore analysé) ; {@code genre} : genre AniList (valeur de {@code GET /api/genres}, §24.6).
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
                          @QueryParam("browser") Boolean browser,
                          @QueryParam("genre") @jakarta.validation.constraints.Size(max = 40) String genre) {
        if (!java.util.Set.of("recent", "title", "year").contains(sort)) {
            throw new ApiException(400, "INVALID_SORT", "sort doit valoir 'title', 'recent' ou 'year'");
        }
        if (watch != null && !WATCH.contains(watch)) {
            throw new ApiException(400, "INVALID_FILTER", "watch doit valoir 'unseen', 'inProgress' ou 'seen'");
        }
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
            throw new ApiException(400, "INVALID_FILTER", "yearFrom doit être inférieur ou égal à yearTo");
        }
        String search = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim()) + "%";
        var result = library.animes(new LibraryRepository.AnimeQuery(sort, search, yearFrom, yearTo, watch,
                watch == null ? null : Long.parseLong(jwt.getSubject()), browser,
                genre == null || genre.isBlank() ? null : genre.trim(), page, size));
        // Affiche : fichier sur le NAS, sinon URL distante (TMDB, puis AniList), sinon null (ARCHITECTURE §17).
        var urls = posters.urls(result.rows().stream().map(LibraryRepository.AnimeRow::id).toList());
        List<AnimeSummary> list = result.rows().stream().map(r -> {
            var u = urls.get(r.id());
            return new AnimeSummary(r.id(), r.title(), r.year(), u == null ? null : u.small(), r.episodeCount(), r.lastAddedAt());
        }).toList();
        return new AnimePage(result.total(), page, size, list);
    }

    /** Échappe les jokers de LIKE (caractère d'échappement : « ! »). */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    @GET
    @Path("/anime/{id}")
    public AnimeDetail anime(@PathParam("id") long id) {
        List<SeasonDto> seasons = seasons(id);
        Anime a = library.findAnime(id);
        // Synopsis : français de TMDB s'il existe (et a moins de 6 mois), sinon anglais d'AniList (ARCHITECTURE §16).
        LibraryRepository.TmdbFrench tmdb = library.tmdbFrench(id, tmdbConfig.maxAge().toSeconds()).orElse(null);
        String frSynopsis = tmdb == null ? null : tmdb.synopsis();
        String frTitle = tmdb == null ? null : tmdb.title();
        String tmdbUrl = tmdb == null || tmdb.tmdbType() == null ? null
                : "https://www.themoviedb.org/" + tmdb.tmdbType() + "/" + tmdb.tmdbId();
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
                frSynopsis != null ? tmdb.language() : a.synopsisLanguage, poster == null ? null : poster.small(),
                poster == null ? null : poster.large(), a.year, anilist,
                a.metadataUrl, seasons, frSynopsis != null ? "TMDB" : a.synopsis != null ? anilist : null, frTitle,
                frSynopsis != null || frTitle != null ? tmdbUrl : null, resume, genresOf(id));
    }

    private List<GenreDto> genresOf(long animeId) {
        return library.genresOf(animeId).stream().map(g -> new GenreDto(g, Genres.label(g)))
                .sorted(java.util.Comparator.comparing(GenreDto::label, java.text.Collator.getInstance(java.util.Locale.FRENCH)))
                .toList();
    }

    /** Genres présents dans la bibliothèque (animés visibles), par libellé français (§24.6). */
    @GET
    @Path("/genres")
    public List<GenreCount> genres() {
        return library.genreCounts().stream()
                .map(r -> new GenreCount(r.genre(), Genres.label(r.genre()), r.animeCount()))
                .sorted(java.util.Comparator.comparing(GenreCount::label, java.text.Collator.getInstance(java.util.Locale.FRENCH)))
                .toList();
    }

    /** Saisons dans l'ordre 1, 2, 3… puis Spéciaux (saison 0) en dernier. */
    @GET
    @Path("/anime/{id}/seasons")
    public List<SeasonDto> seasons(@PathParam("id") long id) {
        List<LibraryRepository.SeasonRow> rows = library.seasons(id);
        if (rows.isEmpty()) {
            throw notFound("ANIME_NOT_FOUND", "Animé introuvable");
        }
        return rows.stream().map(r -> {
            int number = r.seasonNumber();
            return new SeasonDto(r.id(), number, number == 0 ? "Spéciaux" : "Saison " + number, r.episodeCount());
        }).toList();
    }

    @GET
    @Path("/seasons/{id}/episodes")
    public List<EpisodeSummary> episodes(@PathParam("id") long seasonId) {
        List<LibraryRepository.EpisodeRow> list = library.episodes(seasonId);
        if (list.isEmpty()) {
            throw notFound("SEASON_NOT_FOUND", "Saison introuvable");
        }
        java.util.Map<Long, Boolean> playable = library.browserPlayable(seasonId);
        return list.stream().map(e -> new EpisodeSummary(e.id(), e.episodeNumber(), e.title(), e.durationSeconds(),
                playable.get(e.id()))).toList();
    }

    @GET
    @Path("/episodes/{id}")
    public EpisodeDetail episode(@PathParam("id") long id) {
        return library.episode(id)
                .map(e -> new EpisodeDetail(e.id(), e.animeId(), e.animeTitle(), e.seasonId(), e.seasonNumber(),
                        e.episodeNumber(), e.title(), e.synopsis(), e.durationSeconds(), e.container(), e.fileSize()))
                .orElseThrow(() -> notFound("EPISODE_NOT_FOUND", "Épisode introuvable"));
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }
}
