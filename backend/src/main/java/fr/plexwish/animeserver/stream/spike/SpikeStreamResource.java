package fr.plexwish.animeserver.stream.spike;

import fr.plexwish.animeserver.stream.ByteRange;
import fr.plexwish.animeserver.stream.VideoMediaTypes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.PathPart;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Spike phase 0 : sert les vidéos de dev-media avec les HTTP Range Requests.
 * <p>
 * Désactivé par défaut (404). Pas d'authentification : ne jamais activer en production.
 * L'envoi passe par {@link PathPart}, que Quarkus transmet avec {@code sendFile} :
 * le fichier est lu par l'OS, jamais chargé en mémoire.
 */
@Path("/api/dev")
public class SpikeStreamResource {

    public record DevFile(int id, String name, long size, String url) {
    }

    private final SpikeStreamConfig config;

    public SpikeStreamResource(SpikeStreamConfig config) {
        this.config = config;
    }

    /** Liste des vidéos avec l'URL à coller dans l'app Android. */
    @GET
    @Path("/files")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DevFile> files(@Context UriInfo uriInfo) {
        DevMediaIndex index = index();
        return index.list().stream()
                .map(e -> new DevFile(e.id(), e.relativePath(), e.size(),
                        uriInfo.getBaseUriBuilder().path("api/dev/stream/{id}").build(e.id()).toString()))
                .toList();
    }

    @GET
    @Path("/stream/{id}")
    public Response stream(@PathParam("id") String id, @HeaderParam("Range") String range) {
        DevMediaIndex index = index();
        int number;
        try {
            number = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new NotFoundException();
        }
        DevMediaIndex.Entry entry = index.find(number).orElseThrow(NotFoundException::new);
        java.nio.file.Path file = entry.path();
        if (!index.isServable(file)) {
            throw new NotFoundException();
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new NotFoundException();
        }
        String type = VideoMediaTypes.forFileName(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return switch (ByteRange.evaluate(range, size)) {
            case ByteRange.Full full -> Response.ok(new PathPart(file, 0, size), type)
                    .header("Accept-Ranges", "bytes")
                    .header(HttpHeaders.CONTENT_LENGTH, size)
                    .build();
            case ByteRange.Partial(ByteRange r) -> Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(new PathPart(file, r.start(), r.length()))
                    .type(type)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", r.contentRange(size))
                    .header(HttpHeaders.CONTENT_LENGTH, r.length())
                    .build();
            case ByteRange.Unsatisfiable u -> Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes */" + size)
                    .build();
        };
    }

    private DevMediaIndex index() {
        if (!config.enabled()) {
            throw new NotFoundException();
        }
        return new DevMediaIndex(java.nio.file.Path.of(config.mediaDir()));
    }
}
