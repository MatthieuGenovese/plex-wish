package fr.plexwish.animeserver.stream;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.LibraryConfig;
import io.agroal.api.AgroalDataSource;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

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

    public record StreamUrl(String url, Instant expiresAt, String mimeType, long fileSize) {
    }

    @Inject
    StreamSigner signer;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    LibraryConfig library;
    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/episodes/{id}/stream-url")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public StreamUrl streamUrl(@PathParam("id") long episodeId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT m.id, m.available, m.file_name, m.file_size
                     FROM episode e LEFT JOIN media_file m ON m.id = e.media_file_id WHERE e.id = ?""")) {
            st.setLong(1, episodeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable");
                }
                if (rs.getObject(1) == null || !rs.getBoolean(2)) {
                    throw unavailable();
                }
                long fileId = rs.getLong(1);
                StreamSigner.SignedUrl signed = signer.sign(fileId, Long.parseLong(jwt.getSubject()));
                return new StreamUrl(signed.url(), signed.expiresAt(), mimeType(rs.getString(3)), rs.getLong(4));
            }
        }
    }

    @GET
    @Path("/stream/{fileId}")
    @PermitAll
    public Response stream(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                           @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(fileId, userId, exp, sig, range, true);
    }

    /** Certains lecteurs sondent la taille et le type avant de lire : mêmes contrôles, sans corps. */
    @HEAD
    @Path("/stream/{fileId}")
    @PermitAll
    public Response head(@PathParam("fileId") long fileId, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                         @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(fileId, userId, exp, sig, range, false);
    }

    private Response serve(long fileId, Long userId, Long exp, String sig, String range, boolean body) throws SQLException {
        if (userId == null || exp == null) {
            throw invalidUrl();
        }
        switch (signer.check(fileId, userId, exp, sig)) {
            case INVALID -> throw invalidUrl();
            case EXPIRED -> throw new ApiException(403, "STREAM_URL_EXPIRED", "Lien de lecture expiré : en demander un nouveau");
            case VALID -> {
            }
        }
        String relativePath;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT (SELECT enabled FROM app_user WHERE id = ?),
                            (SELECT relative_path FROM media_file WHERE id = ? AND available)""")) {
            st.setLong(1, userId);
            st.setLong(2, fileId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) { // utilisateur supprimé (null → false) ou désactivé depuis l'émission de l'URL
                    throw new ApiException(403, "USER_DISABLED", "Compte désactivé");
                }
                relativePath = rs.getString(2);
            }
        }
        java.nio.file.Path file = resolve(relativePath);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw unavailable();
        }
        String type = mimeType(file.getFileName().toString());
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
