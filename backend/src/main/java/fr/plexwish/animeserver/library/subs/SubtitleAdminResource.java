package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.scan.Titles;
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
import java.sql.SQLException;
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
    ExternalSubtitleRepository repository;
    @Inject
    ExternalSubtitleService subtitles;
    @Inject
    JsonWebToken jwt;

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
        ExternalSubtitleRepository.AdminFilter f = ExternalSubtitleRepository.AdminFilter.valueOf(filter);
        ExternalSubtitleRepository.AdminSummary sum = repository.adminSummary();
        Summary summary = new Summary(sum.total(), sum.linked(), sum.unmatched(), sum.manual(), sum.tooLong());
        long total = repository.adminCount(f, like);
        List<SubtitleDto> items = repository.adminPage(f, like, size, (long) page * size).stream()
                .map(SubtitleAdminResource::dto).toList();
        return new SubtitlePage(summary, total, page, size, items);
    }

    /** Rattache le sous-titre à un épisode (correction manuelle, effet immédiat). */
    @PUT
    @Path("/{id}/episode")
    @Consumes(MediaType.APPLICATION_JSON)
    public SubtitleDto attach(@PathParam("id") long id, @Valid @NotNull AttachRequest request) throws SQLException {
        String path = pathOf(id);
        String title = request.animeTitle() == null || request.animeTitle().isBlank()
                ? path.substring(0, Math.max(0, path.indexOf('/'))) : request.animeTitle();
        Long episodeId = repository.episodeId(Titles.normalize(title), request.seasonNumber(), request.episodeNumber())
                .orElse(null);
        if (episodeId == null) {
            throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable : " + title + ", saison "
                    + request.seasonNumber() + ", épisode " + request.episodeNumber());
        }
        save(path, episodeId, "MANUAL");
        return get(id);
    }

    /** Détache le sous-titre (il ne sera plus proposé ; correction manuelle gardée par les scans). */
    @DELETE
    @Path("/{id}/episode")
    public SubtitleDto detach(@PathParam("id") long id) throws SQLException {
        save(pathOf(id), null, null);
        return get(id);
    }

    /** Retire la correction : association automatique, recalculée tout de suite. */
    @DELETE
    @Path("/{id}/override")
    public SubtitleDto automatic(@PathParam("id") long id) throws SQLException {
        String path = pathOf(id);
        repository.deleteOverride(path);
        ExternalSubtitleService.Link link = subtitles.link(path, subtitles.databaseLookup());
        repository.setEpisode(path, link.episodeId(), link.match());
        return get(id);
    }

    private void save(String path, Long episodeId, String match) throws SQLException {
        repository.saveOverride(path, episodeId, jwt.getName());
        repository.setEpisode(path, episodeId, match);
    }

    private String pathOf(long id) throws SQLException {
        return repository.pathOf(id).orElseThrow(() -> new ApiException(404, "SUBTITLE_NOT_FOUND", "Sous-titre introuvable"));
    }

    private SubtitleDto get(long id) throws SQLException {
        return dto(repository.adminRow(id).orElseThrow());
    }

    private static SubtitleDto dto(ExternalSubtitleRepository.AdminRow r) {
        String path = r.relativePath();
        Long episodeId = r.episodeId();
        EpisodeRef ep = episodeId == null ? null : new EpisodeRef(episodeId, r.animeTitle(), r.seasonNumber(), r.episodeNumber());
        return new SubtitleDto(r.id(), path, path.indexOf('/') < 0 ? null : path.substring(0, path.indexOf('/')),
                r.available(), r.match(), r.manual(), r.forced(), r.language(), ep, number(r.lastCueSeconds()),
                number(r.videoDurationSeconds()), r.tooLong());
    }

    private static Double number(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
