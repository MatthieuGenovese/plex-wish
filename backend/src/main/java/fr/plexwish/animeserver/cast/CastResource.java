package fr.plexwish.animeserver.cast;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.poster.PosterService;
import io.agroal.api.AgroalDataSource;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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

    /** Animé visible : au moins un épisode dont le fichier est disponible (comme la bibliothèque). */
    static final String VISIBLE = """
            EXISTS (SELECT 1 FROM episode e JOIN season s ON s.id = e.season_id JOIN media_file f ON f.id = e.media_file_id
                    WHERE s.anime_id = a.id AND f.available)""";

    @Inject
    AgroalDataSource dataSource;
    @Inject
    CastService service;
    @Inject
    PosterService posters;

    @GET
    @Path("/anime/{id}/cast")
    public AnimeCast cast(@PathParam("id") long animeId) throws SQLException {
        String sourceUrl;
        List<Object[]> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT a.metadata_url FROM anime a WHERE a.id = ? AND " + VISIBLE)) {
                st.setLong(1, animeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable");
                    }
                    sourceUrl = rs.getString(1);
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT ac.role, ac.language, ch.name, ch.native_name, p.provider_id, p.name, p.native_name, p.image_url
                    FROM anime_cast ac JOIN cast_character ch ON ch.id = ac.character_id LEFT JOIN person p ON p.id = ac.person_id
                    WHERE ac.anime_id = ? AND ac.language = ? ORDER BY ac.position""")) {
                st.setLong(1, animeId);
                st.setString(2, CastService.LANGUAGE);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        Object[] r = new Object[8];
                        for (int i = 0; i < 8; i++) {
                            r[i] = rs.getString(i + 1);
                        }
                        rows.add(r);
                    }
                }
            }
        }
        Map<String, String> urls = service.imageUrls(rows.stream().map(r -> (String) r[7]).toList());
        List<CastEntry> items = rows.stream().map(r -> new CastEntry(
                new Character((String) r[2], (String) r[3]), (String) r[0], (String) r[1],
                r[4] == null ? null : new PersonRef((String) r[4], (String) r[5], (String) r[6], urls.get((String) r[7]))))
                .toList();
        return new AnimeCast(items.isEmpty() ? null : "AniList", items.isEmpty() ? null : sourceUrl, items);
    }

    @GET
    @Path("/people/{id}")
    public Person person(@PathParam("id") String id) throws SQLException {
        if (id == null || !id.matches("[0-9]{1,10}")) {
            throw new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable");
        }
        String name;
        String nativeName;
        String image;
        List<Object[]> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            long personId;
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT id, name, native_name, image_url FROM person WHERE provider = ? AND provider_id = ?")) {
                st.setString(1, CastService.PROVIDER);
                st.setString(2, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable");
                    }
                    personId = rs.getLong(1);
                    name = rs.getString(2);
                    nativeName = rs.getString(3);
                    image = rs.getString(4);
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT a.id, a.title, a.year, ac.role, ch.name, ch.native_name
                    FROM anime_cast ac JOIN anime a ON a.id = ac.anime_id JOIN cast_character ch ON ch.id = ac.character_id
                    WHERE ac.person_id = ?""" + " AND " + VISIBLE + """
                     ORDER BY lower(a.title), a.id, ac.position""")) {
                st.setLong(1, personId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[]{rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                                rs.getString(5), rs.getString(6)});
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            // Aucun animé disponible de la bibliothèque : la page n'a rien à montrer.
            throw new ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable");
        }
        Map<String, String> urls = service.imageUrls(image == null ? Set.of() : Set.of(image));
        Map<Long, PosterService.Urls> posterUrls = posters.urls(rows.stream().map(r -> (Long) r[0]).distinct().toList());
        List<PersonRole> roles = rows.stream().map(r -> {
            PosterService.Urls p = posterUrls.get((Long) r[0]);
            return new PersonRole((Long) r[0], (String) r[1], (Integer) r[2], p == null ? null : p.small(),
                    new Character((String) r[4], (String) r[5]), (String) r[3]);
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
