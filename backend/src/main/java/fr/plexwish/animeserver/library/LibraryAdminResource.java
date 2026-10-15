package fr.plexwish.animeserver.library;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.library.scan.ScanRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.scan.LibraryScanner;
import fr.plexwish.animeserver.library.scan.ScanService;
import fr.plexwish.animeserver.library.scan.Titles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration de la bibliothèque : scan, rapport (résumé + liste filtrable), corrections manuelles.
 * Le rapport montre les chemins relatifs (l'admin doit savoir de quel fichier il s'agit) ; les corrections
 * désignent toujours un fichier par son id en base, jamais par un chemin fourni par le client.
 */
@Path("/api/admin/library")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class LibraryAdminResource {

    public static final Set<String> CATEGORIES = Set.of(
            "UNRESOLVED", "DUPLICATE", "MULTI_EPISODE", "DECIMAL_EPISODE", "SEASON_MISMATCH", "MISSING", "UNREADABLE",
            "SUBTITLE_UNMATCHED", "SUBTITLE_DURATION");

    public record ScanStarted(long scanId) {
    }

    /**
     * {@code failureCode} (si FAILED) : MEDIA_ROOT_UNAVAILABLE, MASS_REMOVAL (relancer avec confirmMassRemoval=true
     * si c'est voulu), INTERRUPTED, INTERNAL_ERROR. {@code failureReason} : explication pour l'humain.
     */
    public record ScanReport(long id, String status, Instant startedAt, Instant finishedAt, String triggeredBy,
                             String failureCode, String failureReason, JsonNode stats, Map<String, Long> issueCounts) {
    }

    public record ScanRunPage(long total, int page, int size, List<ScanReport> items) {
    }

    /**
     * Fichier signalé. Pour un doublon : {@code relativePath} = fichier écarté, {@code keptRelativePath} = fichier
     * conservé, et l'origine du numéro de saison de chacun (NAME_SXXEXX, NAME_NXEE, NAME_S, FOLDER,
     * SPECIAL_FOLDER, DEFAULT, OVERRIDE) pour repérer un sous-dossier non reconnu comme saison.
     */
    public record IssueDto(long id, Long mediaFileId, String category, String animeTitle, String relativePath, String detail,
                           Integer seasonNumber, Integer episodeNumber, String keptRelativePath,
                           String seasonSource, String keptSeasonSource) {
    }

    public record IssuePage(long scanId, long total, int page, int size, List<IssueDto> items) {
    }

    /** EPISODE : animeTitle, seasonNumber, episodeNumber obligatoires. EXTRA / IGNORE : aucun champ. */
    public record OverrideRequest(
            @NotNull @Pattern(regexp = "EPISODE|EXTRA|IGNORE") String action,
            @Size(min = 1, max = 300) String animeTitle,
            @Min(0) @Max(99) Integer seasonNumber,
            @Min(0) @Max(9999) Integer episodeNumber) {
    }

    /** Fichier qui serait délié par une correction ; {@code viaOverride} : lié (ou à lier) par une autre correction. */
    public record LinkedFile(Long mediaFileId, String relativePath, boolean viaOverride) {
    }

    public record TargetEpisode(String animeTitle, int seasonNumber, int episodeNumber) {
    }

    /** 409 : l'épisode visé est déjà fourni par un autre fichier. Rien n'a été modifié. */
    public record OverrideConflict(int status, String error, String message, TargetEpisode episode,
                                   List<LinkedFile> currentFiles, LinkedFile targetFile) {
    }

    public record OverrideDto(Long mediaFileId, String relativePath, String action, String animeTitle,
                              Integer seasonNumber, Integer episodeNumber, String createdBy, Instant createdAt) {
    }

    @Inject
    ScanService scans;
    @Inject
    ScanRunRepository runs;
    @Inject
    OverrideRepository overrides;
    @Inject
    Db db;
    @Inject
    ObjectMapper json;
    @Inject
    JsonWebToken jwt;

    /**
     * Lance un scan en tâche de fond : 202 + id ; 409 si un scan tourne déjà.
     * {@code confirmMassRemoval=true} : accepter qu'il rende indisponibles plus de la moitié des fichiers
     * connus (sinon le scan s'arrête en FAILED sans rien modifier).
     */
    @POST
    @Path("/scan")
    public Response scan(@QueryParam("confirmMassRemoval") @DefaultValue("false") boolean confirmMassRemoval) {
        return Response.accepted(new ScanStarted(scans.start(jwt.getName(), confirmMassRemoval))).build();
    }

    /** Dernier scan (ou celui demandé) : statut, compteurs par catégorie, nombre de problèmes par catégorie. */
    @GET
    @Path("/scan-report")
    public ScanReport report(@QueryParam("scanId") Long scanId) throws SQLException, IOException {
        long id = scanId != null ? scanId : latestScanId();
        ScanReport report = report(runs.find(id).orElseThrow(() -> new ApiException(404, "SCAN_NOT_FOUND", "Scan introuvable")));
        report.issueCounts().putAll(runs.issueCounts(id));
        return report;
    }

    /** Historique des scans, du plus récent au plus ancien (sans le détail des problèmes). */
    @GET
    @Path("/scans")
    public ScanRunPage history(@QueryParam("page") @DefaultValue("0") @Min(0) int page,
                               @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) throws SQLException, IOException {
        long total = runs.count();
        List<ScanReport> items = new ArrayList<>();
        for (ScanRunRepository.RunRow r : runs.page(size, (long) page * size)) {
            items.add(report(r));
        }
        Map<Long, Map<String, Long>> counts = runs.issueCounts(items.stream().map(ScanReport::id).toList());
        items.forEach(r -> r.issueCounts().putAll(counts.getOrDefault(r.id(), Map.of())));
        return new ScanRunPage(total, page, size, items);
    }

    private ScanReport report(ScanRunRepository.RunRow r) throws IOException {
        String stats = r.statsJson();
        return new ScanReport(r.id(), r.status(), r.startedAt(), r.finishedAt(), r.triggeredBy(),
                r.failureCode(), r.failureReason(), stats == null ? null : json.readTree(stats), new LinkedHashMap<>());
    }

    /** Liste filtrable des fichiers signalés (par catégorie et par animé), paginée. */
    @GET
    @Path("/issues")
    public IssuePage issues(@QueryParam("scanId") Long scanId,
                            @QueryParam("category") String category,
                            @QueryParam("anime") String anime,
                            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                            @QueryParam("size") @DefaultValue("100") @Min(1) @Max(500) int size) throws SQLException {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new ApiException(400, "INVALID_CATEGORY", "Catégorie inconnue : " + String.join(", ", CATEGORIES));
        }
        long id = scanId != null ? scanId : latestScanId();
        String like = anime == null || anime.isBlank() ? null
                : anime.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        long total = runs.countIssues(id, category, like);
        List<IssueDto> items = runs.issues(id, category, like, size, (long) page * size).stream()
                .map(r -> new IssueDto(r.id(), r.mediaFileId(), r.category(), r.animeTitle(), r.relativePath(), r.detail(),
                        r.seasonNumber(), r.episodeNumber(), r.keptRelativePath(), r.seasonSource(), r.keptSeasonSource()))
                .toList();
        return new IssuePage(id, total, page, size, items);
    }

    /**
     * Correction manuelle d'un fichier, appliquée au prochain scan et jamais écrasée par un scan (§7.7).
     * Si l'épisode visé est déjà fourni par un autre fichier (lien actuel ou autre correction) : 409 avec le détail,
     * sans rien modifier, sauf {@code replace=true}. Le remplacement supprime alors la correction de l'autre
     * fichier s'il y en a une ; l'autre fichier reste disponible et ressort au rapport (doublon ou non résolu).
     */
    @PUT
    @Path("/files/{id}/override")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setOverride(@PathParam("id") long mediaFileId,
                                @QueryParam("replace") @DefaultValue("false") boolean replace,
                                @Valid @NotNull OverrideRequest request) throws SQLException {
        boolean episode = "EPISODE".equals(request.action());
        if (episode && (request.animeTitle() == null || request.animeTitle().isBlank()
                || request.seasonNumber() == null || request.episodeNumber() == null)) {
            throw new ApiException(400, "INCOMPLETE_OVERRIDE", "EPISODE demande animeTitle, seasonNumber et episodeNumber");
        }
        String createdBy = jwt.getName();
        OverrideConflict conflict = db.inTransaction(c -> {
            String path = pathOf(c, mediaFileId);
            String title = episode ? request.animeTitle().trim() : null;
            if (episode) {
                List<LinkedFile> conflicts = conflicts(c, path, title, request.seasonNumber(), request.episodeNumber());
                if (!conflicts.isEmpty() && !replace) {
                    TargetEpisode target = new TargetEpisode(displayTitle(c, title), request.seasonNumber(), request.episodeNumber());
                    return new OverrideConflict(409, "EPISODE_ALREADY_LINKED",
                            "Cet épisode est déjà fourni par un autre fichier. Confirmer le remplacement avec replace=true.",
                            target, conflicts, new LinkedFile(mediaFileId, path, false));
                }
                for (LinkedFile other : conflicts) {
                    if (other.viaOverride()) {
                        overrides.delete(c, other.relativePath());
                    }
                }
            }
            overrides.upsert(c, path, request.action(), title, episode ? request.seasonNumber() : null,
                    episode ? request.episodeNumber() : null, createdBy);
            return null;
        });
        if (conflict != null) {
            return Response.status(409).type(MediaType.APPLICATION_JSON).entity(conflict).build();
        }
        return Response.ok(overrideDtos(mediaFileId).get(0)).build();
    }

    /**
     * Autres fichiers disponibles qui fournissent (ou fourniront au prochain scan) l'épisode visé :
     * le fichier actuellement lié, sauf si sa propre correction l'envoie ailleurs, et les fichiers dont une
     * correction vise ce même épisode.
     */
    private List<LinkedFile> conflicts(Connection c, String path, String title, int season, int episode) throws SQLException {
        String target = LibraryScanner.target(title, season, episode);
        Map<String, LinkedFile> found = new LinkedHashMap<>();
        for (OverrideRepository.LinkedRow r : overrides.linkedTo(c, Titles.normalize(title), season, episode, path)) {
            String action = r.action();
            boolean keeps = action == null || ("EPISODE".equals(action)
                    && target.equals(LibraryScanner.target(r.animeTitle(), r.seasonNumber(), r.episodeNumber())));
            if (keeps) {
                found.put(r.relativePath(), new LinkedFile(r.mediaFileId(), r.relativePath(), action != null));
            }
        }
        for (OverrideRepository.OverrideTarget o : overrides.episodeOverridesFor(c, season, episode, path)) {
            if (Titles.normalize(o.animeTitle()).equals(Titles.normalize(title))) {
                found.putIfAbsent(o.relativePath(), new LinkedFile(o.mediaFileId(), o.relativePath(), true));
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Titre tel qu'affiché dans la bibliothèque s'il existe déjà (« Show » pour « show »). */
    private String displayTitle(Connection c, String title) throws SQLException {
        return overrides.animeTitle(c, Titles.normalize(title)).orElse(title);
    }

    @DELETE
    @Path("/files/{id}/override")
    public Response deleteOverride(@PathParam("id") long mediaFileId) throws SQLException {
        String path = overrides.pathOf(mediaFileId).orElseThrow(LibraryAdminResource::fileNotFound);
        if (overrides.delete(path) == 0) {
            throw new ApiException(404, "OVERRIDE_NOT_FOUND", "Aucune correction pour ce fichier");
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/overrides")
    public List<OverrideDto> listOverrides() throws SQLException {
        return overrideDtos(null);
    }

    private List<OverrideDto> overrideDtos(Long mediaFileId) throws SQLException {
        return overrides.list(mediaFileId).stream()
                .map(o -> new OverrideDto(o.mediaFileId(), o.relativePath(), o.action(), o.animeTitle(), o.seasonNumber(),
                        o.episodeNumber(), o.createdBy(), o.createdAt()))
                .toList();
    }

    private String pathOf(Connection c, long mediaFileId) throws SQLException {
        return overrides.pathOf(c, mediaFileId).orElseThrow(LibraryAdminResource::fileNotFound);
    }

    private static ApiException fileNotFound() {
        return new ApiException(404, "FILE_NOT_FOUND", "Fichier introuvable");
    }

    private long latestScanId() throws SQLException {
        return runs.latestId().orElseThrow(() -> new ApiException(404, "NO_SCAN", "Aucun scan n'a encore été lancé"));
    }
}
