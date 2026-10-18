package fr.plexwish.animeserver.live;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;

/**
 * Ressources HLS de la lecture à la volée (docs/LIVE-REMUX.md §5), toutes signées ({@code v1-live}) :
 * {@code master.m3u8}, {@code v.m3u8}, {@code a{N}.m3u8}, {@code {piste}/init.mp4}, {@code {piste}/{n}.m4s}.
 * Noms vérifiés par les expressions des chemins ; aucun chemin de fichier ne vient du client.
 */
// Préfixe de classe « /api » comme les autres ressources : un préfixe « /api/stream/{fileId}/… » au niveau de la classe
// capte aussi « /api/stream/{fileId} » (routage de RESTEasy Reactive) et casse la lecture des originaux.
@Path("/api")
@PermitAll
public class LiveStreamResource {

    static final String M3U8 = "application/vnd.apple.mpegurl";

    @Inject
    LiveService live;

    @GET
    @Path("/stream/{fileId}/live/{name: master|v|a[0-9]+}.m3u8")
    public Response playlist(@PathParam("fileId") long fileId, @PathParam("name") String name, @QueryParam("u") Long userId,
                             @QueryParam("exp") Long exp, @QueryParam("sig") String sig) throws SQLException {
        live.authorize(fileId, userId, exp, sig);
        String text = name.equals("master") ? live.master(fileId, userId, exp) : live.mediaPlaylist(fileId, userId, exp, name);
        // Les playlists contiennent des signatures : jamais gardées par un cache.
        return Response.ok(text).type(M3U8).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/stream/{fileId}/live/{track: v|a[0-9]+}/init.mp4")
    public Response init(@PathParam("fileId") long fileId, @PathParam("track") String track, @QueryParam("u") Long userId,
                         @QueryParam("exp") Long exp, @QueryParam("sig") String sig) throws SQLException {
        live.authorize(fileId, userId, exp, sig);
        byte[] header = live.init(fileId, userId, track);
        return Response.ok(header).type(track.equals("v") ? "video/mp4" : "audio/mp4")
                .header("Cache-Control", "private, max-age=0").build();
    }

    /** Piste texte du fichier, extraite (Android) : ASS ou WebVTT. */
    @GET
    @Path("/stream/{fileId}/live/sub/{index: [0-9]+}.{ext: ass|vtt}")
    public Response subtitle(@PathParam("fileId") long fileId, @PathParam("index") int index, @PathParam("ext") String ext,
                             @QueryParam("u") Long userId, @QueryParam("exp") Long exp, @QueryParam("sig") String sig)
            throws SQLException {
        live.authorize(fileId, userId, exp, sig);
        java.nio.file.Path file = live.subtitle(fileId, index, ext);
        return Response.ok(file.toFile()).type((ext.equals("ass") ? "text/x-ssa" : "text/vtt") + "; charset=utf-8")
                .header("Cache-Control", "private, max-age=0").header("X-Content-Type-Options", "nosniff").build();
    }

    @GET
    @Path("/stream/{fileId}/live/{track: v|a[0-9]+}/{index: [0-9]+}.m4s")
    public Response segment(@PathParam("fileId") long fileId, @PathParam("track") String track, @PathParam("index") int index,
                            @QueryParam("u") Long userId, @QueryParam("exp") Long exp, @QueryParam("sig") String sig)
            throws SQLException {
        live.authorize(fileId, userId, exp, sig);
        java.nio.file.Path file = live.segment(fileId, userId, track, index);
        return Response.ok(file.toFile()).type(track.equals("v") ? "video/mp4" : "audio/mp4")
                .header("Cache-Control", "private, max-age=0").build();
    }
}
