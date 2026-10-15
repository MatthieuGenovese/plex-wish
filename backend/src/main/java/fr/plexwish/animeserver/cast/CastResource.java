package fr.plexwish.animeserver.cast;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.poster.PosterService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Distribution d'un animé et page comédien (ARCHITECTURE §19.4). Un comédien est identifié par son identifiant
 * AniList. Sa page ne liste que les animés de la bibliothèque qui ont au moins un épisode disponible.
 */
@Path("/api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class CastResource {

    /** Personnage : nom seulement, jamais d'image (seuls les comédiens ont une photo). */
    public record Character(String name, String nativeName) {
    }

    public record PersonRef(String id, String name, String nativeName, String imageUrl) {
    }

    public record CastEntry(Character character, String role, String language, PersonRef person) {
    }

    public record AnimeCast(String source, String sourceUrl, List<CastEntry> items) {
    }

    public record PersonRole(long animeId, String animeTitle, Integer year, String posterUrl, Character character, String role) {
    }

    public record Person(String id, String name, String nativeName, String imageUrl, String sourceUrl, List<PersonRole> roles) {
    }

    @Inject
    CastRepository repository;
    @Inject
    CastService service;
    @Inject
    PosterService posters;

    @GET
    @Path("/anime/{id}/cast")
    public AnimeCast cast(@PathParam("id") long animeId) throws SQLException {
        String sourceUrl = repository.visibleAnime(animeId)
                .orElseThrow(() -> new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable")).metadataUrl();
        List<CastRepository.CastRow> rows = repository.castOf(animeId, CastService.LANGUAGE);
        Map<String, String> urls = service.imageUrls(rows.stream().map(CastRepository.CastRow::personImageUrl).toList());
        List<CastEntry> items = rows.stream().map(r -> new CastEntry(
                new Character(r.characterName(), r.characterNativeName()), r.role(), r.language(),
                r.personProviderId() == null ? null
                        : new PersonRef(r.personProviderId(), r.personName(), r.personNativeName(), urls.get(r.personImageUrl()))))
                .toList();
        return new AnimeCast(items.isEmpty() ? null : "AniList", items.isEmpty() ? null : sourceUrl, items);
    }

    @GET
    @Path("/people/{id}")
    public Person person(@PathParam("id") String id) throws SQLException {
        if (id == null || !id.matches("[0-9]{1,10}")) {
            throw new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable");
        }
        CastRepository.PersonRow person = repository.person(CastService.PROVIDER, id)
                .orElseThrow(() -> new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable"));
        String name = person.name();
        String nativeName = person.nativeName();
        String image = person.imageUrl();
        List<CastRepository.RoleRow> rows = repository.visibleRolesOf(person.id());
        if (rows.isEmpty()) {
            // Aucun animé disponible de la bibliothèque : la page n'a rien à montrer.
            throw new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable");
        }
        Map<String, String> urls = service.imageUrls(image == null ? Set.of() : Set.of(image));
        Map<Long, PosterService.Urls> posterUrls = posters.urls(rows.stream().map(CastRepository.RoleRow::animeId).distinct().toList());
        List<PersonRole> roles = rows.stream().map(r -> {
            PosterService.Urls p = posterUrls.get(r.animeId());
            return new PersonRole(r.animeId(), r.animeTitle(), r.year(), p == null ? null : p.small(),
                    new Character(r.characterName(), r.characterNativeName()), r.role());
        }).toList();
        return new Person(id, name, nativeName, urls.get(image), "https://anilist.co/staff/" + id, roles);
    }

    /** Photo locale d'un comédien (sans authentification, identifiant aléatoire : voir §17.3). */
    @GET
    @Path("/cast-images/{publicId}")
    @PermitAll
    public Response image(@PathParam("publicId") String publicId) throws SQLException {
        if (publicId == null || !publicId.matches("[0-9a-f]{32}")) {
            return Response.status(404).build();
        }
        return service.served(publicId)
                .map(s -> Response.ok(s.file().toFile(), s.contentType())
                        .header("Cache-Control", "private, max-age=2592000, immutable")
                        .header("ETag", "\"" + s.sha256() + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .build())
                .orElseGet(() -> Response.status(404).build());
    }
}
