package fr.plexwish.animeserver.stream;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.LibraryConfig;
import fr.plexwish.animeserver.library.parse.AssTiming;
import fr.plexwish.animeserver.library.subs.ExternalSubtitleService;
import fr.plexwish.animeserver.media.RemuxCache;
import fr.plexwish.animeserver.media.RemuxService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.PathPart;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lecture vidéo (ARCHITECTURE §6) en deux temps :
 * <ol>
 *     <li>{@code GET /api/episodes/{id}/stream-url} (Bearer) : URL signée, valable quelques heures ;</li>
 *     <li>{@code GET /api/stream/{fichier}?u=&exp=&sig=} (sans en-tête : un lecteur vidéo ne sait pas en envoyer) :
 *     le fichier, avec les Range Requests.</li>
 * </ol>
 * À chaque requête de lecture (donc à chaque seek) : signature, expiration, utilisateur actif, fichier disponible.
 * Le chemin vient de la base, jamais du client, et doit rester sous la racine de la bibliothèque.
 * Sur un 403, le client redemande une URL (pause longue, URL expirée).
 */
@Path("/api")
public class StreamResource {

    /**
     * {@code subtitles} : sous-titres externes (.ass) associés à l'épisode, chacun avec son URL signée (1.3.1), complets
     * d'abord, le premier complet par défaut.
     */
    public record StreamUrl(String url, Instant expiresAt, String mimeType, long fileSize, List<SubtitleLink> subtitles) {
        StreamUrl(String url, Instant expiresAt, String mimeType, long fileSize) {
            this(url, expiresAt, mimeType, fileSize, List.of());
        }
    }

    /** Sous-titre externe pour le lecteur Android ; {@code mimeType} : {@code text/x-ssa}. */
    public record SubtitleLink(long id, String url, String label, String language, boolean forced, boolean isDefault,
                               String mimeType) {
    }

    @Inject
    PlaybackActivity playback;
    @Inject
    StreamSigner signer;
    @Inject
    StreamRepository streams;
    @Inject
    LibraryConfig library;
    @Inject
    JsonWebToken jwt;
    @Inject
    RemuxService remux;
    @Inject
    RemuxCache remuxCache;
    @Inject
    ExternalSubtitleService externalSubtitles;

    /** Préparation en cours (copie remuxée pour Android, §23) : réponse 202, distincte d'une erreur. */
    public record Preparing(String state, int position, Double progress, long estimatedSeconds, int retryAfterSeconds, String message) {
    }

    @GET
    @Path("/episodes/{id}/stream-url")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response streamUrl(@PathParam("id") long episodeId) throws SQLException {
        StreamRepository.EpisodeFile file = streams.episodeFile(episodeId)
                .orElseThrow(() -> new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable"));
        if (file.fileId() == null || !file.available()) {
            throw unavailable();
        }
        long fileId = file.fileId();
        String fileName = file.fileName();
        long size = file.fileSize();
        String container = file.container();
        java.time.OffsetDateTime modified = file.lastModified();
        long userId = Long.parseLong(jwt.getSubject());
        List<SubtitleLink> subs = subtitles(fileId, userId);
        if (!remux.needsRemux(fileId, container)) {
            StreamSigner.SignedUrl signed = signer.sign(fileId, userId);
            return Response.ok(new StreamUrl(signed.url(), signed.expiresAt(), mimeType(fileName), size, subs)).build();
        }
        // Fichier illisible tel quel sur Android (AVI, OGM) : jamais l'URL de l'original, toujours la copie remuxée.
        return switch (remux.request(fileId, size, modified)) {
            case RemuxService.Ready r -> {
                StreamSigner.SignedUrl signed = signer.sign(fileId, userId, StreamSigner.Target.REMUX);
                yield Response.ok(new StreamUrl(signed.url(), signed.expiresAt(), "video/x-matroska",
                        remuxCache.size(r.key()), subs)).build();
            }
            case RemuxService.Preparing p -> Response.status(Response.Status.ACCEPTED)
                    .header("Retry-After", p.retryAfterSeconds())
                    .entity(new Preparing("PREPARING", p.position(), p.progress(), p.estimatedSeconds(), p.retryAfterSeconds(),
                            p.position() == 0 ? "Préparation de l'épisode…"
                                    : "Préparation de l'épisode… (" + p.position() + " avant lui)"))
                    .build();
            case RemuxService.CacheFull f -> throw new ApiException(503, "REMUX_CACHE_FULL",
                    "Le serveur n'a plus de place pour préparer cet épisode (les copies déjà prêtes sont en cours de lecture). "
                            + "Réessayez dans quelques minutes.");
            case RemuxService.Failed f -> throw new ApiException(409, "REMUX_FAILED",
                    "Ce fichier n'a pas pu être converti pour Android (" + f.reason() + "). L'administrateur peut relancer la conversion.");
            case RemuxService.Unavailable u -> throw new ApiException(503, "REMUX_UNAVAILABLE",
                    "Ce fichier doit être converti pour Android, mais la conversion est indisponible sur le serveur (" + u.reason() + ").");
            case RemuxService.NotNeeded n -> {
                StreamSigner.SignedUrl signed = signer.sign(fileId, userId);
                yield Response.ok(new StreamUrl(signed.url(), signed.expiresAt(), mimeType(fileName), size, subs)).build();
            }
        };
    }

    /** Sous-titres externes de l'épisode, URL signées (même durée de vie que celle de la vidéo). */
    private List<SubtitleLink> subtitles(long fileId, long userId) throws SQLException {
        long exp = signer.expiry();
        List<SubtitleLink> out = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        boolean defaultTaken = false;
        for (ExternalSubtitleService.External x : externalSubtitles.forFile(fileId)) {
            boolean isDefault = !x.forced() && !defaultTaken;
            defaultTaken |= isDefault;
            String label = ExternalSubtitleService.label(x.language(), x.forced());
            String unique = label;
            for (int i = 2; !labels.add(unique); i++) {
                unique = label + " " + i;
            }
            out.add(new SubtitleLink(x.id(), signer.subtitleUrl(fileId, x.id(), userId, exp), unique, x.language(), x.forced(),
                    isDefault, SSA));
        }
        return out;
    }

    static final String SSA = "text/x-ssa";

    /**
     * Sous-titre externe (Android, 1.3.1) : signature liée au fichier et au sous-titre ; le sous-titre doit être associé
     * à l'épisode de ce fichier et disponible ; chemin venu de la base, revérifié sous la bibliothèque. Texte brut, petit :
     * envoyé en entier (pas de Range).
     */
    @GET
    @Path("/stream/{fileId}/subtitle/{subId}")
    @PermitAll
    public Response subtitle(@PathParam("fileId") long fileId, @PathParam("subId") long subId, @QueryParam("u") Long userId,
                             @QueryParam("exp") Long exp, @QueryParam("sig") String sig) throws SQLException {
        if (userId == null || exp == null) {
            throw invalidUrl();
        }
        switch (signer.checkSubtitle(fileId, subId, userId, exp, sig)) {
            case INVALID -> throw invalidUrl();
            case EXPIRED -> throw new ApiException(403, "STREAM_URL_EXPIRED", "Lien de lecture expiré : en demander un nouveau");
            case VALID -> {
            }
        }
        StreamRepository.FileAccess access = streams.subtitleAccess(userId, subId, fileId);
        if (!access.userEnabled()) {
            throw new ApiException(403, "USER_DISABLED", "Compte désactivé");
        }
        String relativePath = access.relativePath();
        java.nio.file.Path file = externalSubtitles.resolve(relativePath).orElseThrow(StreamResource::unavailable);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw unavailable();
        }
        if (size > AssTiming.MAX_BYTES) {
            throw unavailable();
        }
        return Response.ok(new PathPart(file, 0, size)).type(SSA + "; charset=utf-8")
                .header(HttpHeaders.CONTENT_LENGTH, size).header("Cache-Control", "private, max-age=0")
                .header("X-Content-Type-Options", "nosniff").build();
    }

    @GET
    @Path("/stream/{fileId}")
    @PermitAll
    public Response stream(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                           @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(StreamSigner.Target.ORIGINAL, fileId, userId, exp, sig, range, true);
    }

    /** Certains lecteurs sondent la taille et le type avant de lire : mêmes contrôles, sans corps. */
    @HEAD
    @Path("/stream/{fileId}")
    @PermitAll
    public Response head(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                         @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(StreamSigner.Target.ORIGINAL, fileId, userId, exp, sig, range, false);
    }

    /** Copie remuxée (cache, §23) : même signature, liée à la copie (une URL de l'original ne l'ouvre pas). */
    @GET
    @Path("/stream/{fileId}/remux")
    @PermitAll
    public Response streamRemux(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                                @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(StreamSigner.Target.REMUX, fileId, userId, exp, sig, range, true);
    }

    @HEAD
    @Path("/stream/{fileId}/remux")
    @PermitAll
    public Response headRemux(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                              @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(StreamSigner.Target.REMUX, fileId, userId, exp, sig, range, false);
    }

    private Response serve(StreamSigner.Target target, long fileId, Long userId, Long exp, String sig, String range, boolean body)
            throws SQLException {
        if (userId == null || exp == null) {
            throw invalidUrl();
        }
        switch (signer.check(target, fileId, userId, exp, sig)) {
            case INVALID -> throw invalidUrl();
            case EXPIRED -> throw new ApiException(403, "STREAM_URL_EXPIRED", "Lien de lecture expiré : en demander un nouveau");
            case VALID -> {
            }
        }
        playback.mark();
        StreamRepository.FileAccess access = streams.mediaFileAccess(userId, fileId);
        if (!access.userEnabled()) { // utilisateur supprimé (null → false) ou désactivé depuis l'émission de l'URL
            throw new ApiException(403, "USER_DISABLED", "Compte désactivé");
        }
        String relativePath = access.relativePath();
        long sourceSize = access.fileSize();
        java.time.OffsetDateTime sourceModified = access.lastModified();
        java.nio.file.Path file = target == StreamSigner.Target.ORIGINAL ? resolve(relativePath)
                : relativePath == null ? null : remux.served(fileId, sourceSize, sourceModified).orElse(null);
        if (file == null) {
            // Copie effacée du cache (ou source modifiée) : le lecteur redemande un lien, la copie est refaite.
            throw new ApiException(404, "REMUX_NOT_READY", "Copie de lecture indisponible : en demander un nouveau lien");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw unavailable();
        }
        String type = target == StreamSigner.Target.REMUX ? "video/x-matroska" : mimeType(file.getFileName().toString());
        return switch (ByteRange.evaluate(range, size)) {
            case ByteRange.Full full -> headers(Response.ok(), type)
                    .entity(body ? new PathPart(file, 0, size) : null)
                    .header(HttpHeaders.CONTENT_LENGTH, size)
                    .build();
            case ByteRange.Partial(ByteRange r) -> headers(Response.status(Response.Status.PARTIAL_CONTENT), type)
                    .entity(body ? new PathPart(file, r.start(), r.length()) : null)
                    .header("Content-Range", r.contentRange(size))
                    .header(HttpHeaders.CONTENT_LENGTH, r.length())
                    .build();
            case ByteRange.Unsatisfiable u -> Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes */" + size)
                    .build();
        };
    }

    /**
     * Chemin réel sous la racine de la bibliothèque, sinon 404. Le chemin relatif vient de la base (écrit par le
     * scan), mais on revérifie quand même : « .. », chemin absolu, lien symbolique qui sort de la racine.
     */
    java.nio.file.Path resolve(String relativePath) {
        if (relativePath == null) {
            throw unavailable();
        }
        try {
            java.nio.file.Path root = java.nio.file.Path.of(library.mediaRoot()).toRealPath();
            java.nio.file.Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root)) {
                throw unavailable();
            }
            java.nio.file.Path real = candidate.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw unavailable();
            }
            return real;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw unavailable();
        }
    }

    private static Response.ResponseBuilder headers(Response.ResponseBuilder b, String type) {
        // private : un cache partagé (proxy) ne doit pas garder la vidéo d'un utilisateur.
        return b.type(type).header("Accept-Ranges", "bytes").header("Cache-Control", "private, max-age=0");
    }

    private static String mimeType(String fileName) {
        return VideoMediaTypes.forFileName(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private static ApiException invalidUrl() {
        return new ApiException(403, "STREAM_URL_INVALID", "Lien de lecture invalide : en demander un nouveau");
    }

    private static ApiException unavailable() {
        return new ApiException(404, "EPISODE_UNAVAILABLE", "Cet épisode n'est pas disponible pour le moment (fichier absent du serveur)");
    }
}
