package fr.plexwish.animeserver.cast;

import fr.plexwish.animeserver.common.db.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Distribution (personnages, comédiens, rôles par animé) : tables anime_cast, cast_character, person. */
@ApplicationScoped
public class CastRepository {

    /** Animé visible : au moins un épisode dont le fichier est disponible (comme la bibliothèque). */
    static final String VISIBLE = """
            EXISTS (SELECT 1 FROM episode e JOIN season s ON s.id = e.season_id JOIN media_file f ON f.id = e.media_file_id
                    WHERE s.anime_id = a.id AND f.available)""";

    /** Ligne de distribution d'un animé ({@code personProviderId} null : personnage sans comédien connu). */
    public record CastRow(String role, String language, String characterName, String characterNativeName,
                          String personProviderId, String personName, String personNativeName, String personImageUrl) {
    }

    public record PersonRow(long id, String name, String nativeName, String imageUrl) {
    }

    /** Rôle d'un comédien dans un animé visible. */
    public record RoleRow(long animeId, String animeTitle, Integer year, String role, String characterName,
                          String characterNativeName) {
    }

    /** {@code metadataUrl} peut être null : enveloppé pour distinguer « animé absent ». */
    public record VisibleAnime(String metadataUrl) {
    }

    @Inject
    Db db;

    /** Animé visible (vide sinon), avec l'adresse de sa fiche. */
    public Optional<VisibleAnime> visibleAnime(long animeId) throws SQLException {
        return db.one("SELECT a.metadata_url FROM anime a WHERE a.id = ? AND " + VISIBLE,
                rs -> new VisibleAnime(rs.getString(1)), animeId);
    }

    public List<CastRow> castOf(long animeId, String language) throws SQLException {
        return db.list("""
                SELECT ac.role, ac.language, ch.name, ch.native_name, p.provider_id, p.name, p.native_name, p.image_url
                FROM anime_cast ac JOIN cast_character ch ON ch.id = ac.character_id LEFT JOIN person p ON p.id = ac.person_id
                WHERE ac.anime_id = ? AND ac.language = ? ORDER BY ac.position""",
                rs -> new CastRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)),
                animeId, language);
    }

    public Optional<PersonRow> person(String provider, String providerId) throws SQLException {
        return db.one("SELECT id, name, native_name, image_url FROM person WHERE provider = ? AND provider_id = ?",
                rs -> new PersonRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), provider, providerId);
    }

    /** Rôles du comédien dans les animés visibles, par titre. */
    public List<RoleRow> visibleRolesOf(long personId) throws SQLException {
        return db.list("""
                SELECT a.id, a.title, a.year, ac.role, ch.name, ch.native_name
                FROM anime_cast ac JOIN anime a ON a.id = ac.anime_id JOIN cast_character ch ON ch.id = ac.character_id
                WHERE ac.person_id = ?""" + " AND " + VISIBLE + """
                 ORDER BY lower(a.title), a.id, ac.position""",
                rs -> new RoleRow(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)),
                personId);
    }
}
