package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.library.LibraryConfig;
import fr.plexwish.animeserver.library.parse.AssTiming;
import fr.plexwish.animeserver.library.parse.FilenameParser;
import fr.plexwish.animeserver.library.parse.ParseResult;
import fr.plexwish.animeserver.library.parse.SubtitleNames;
import fr.plexwish.animeserver.library.scan.Titles;
import fr.plexwish.animeserver.webplay.WebDecision;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Sous-titres externes (1.3.0, ARCHITECTURE §7.8) : association d'un {@code .ass} à un épisode, lecture de ce qui est
 * en base pour la lecture (navigateur, Android), polices du dossier. Les fichiers ne sont jamais modifiés ; un chemin
 * vient toujours de la base (écrit par le scan) et il est revérifié sous la racine de la bibliothèque.
 */
@ApplicationScoped
public class ExternalSubtitleService {

    /** Nombre de polices prises dans le dossier d'un sous-titre (les plafonds de taille du cache web s'appliquent ensuite). */
    public static final int MAX_FONTS = 60;

    @Inject
    AgroalDataSource dataSource;
    @Inject
    LibraryConfig config;
    @Inject
    FilenameParser parser;

    /** Sous-titre externe associé (ce qu'il faut pour le servir). */
    public record External(long id, String relativePath, long size, OffsetDateTime modified, boolean forced, String language) {
    }

    /** Association : épisode (null : aucun) et façon dont il a été trouvé ; {@code season} / {@code episode} : lus dans le nom. */
    public record Link(Long episodeId, String match, Integer season, Integer episode) {
        static final Link NONE = new Link(null, null, null, null);
    }

    /** Recherche d'épisodes, chargée une fois par scan (ou à la demande pour une correction). */
    public interface Lookup {
        /** Épisode de la vidéo de ce dossier dont le nom (sans extension, minuscules) est {@code stem}. */
        Long sameName(String dir, String stem);

        Long episode(String normalizedTitle, int season, int episode);

        /** Plus grand numéro d'épisode de la saison, null si elle n'existe pas. */
        Integer lastEpisode(String normalizedTitle, int season);
    }

    // --- Association ----------------------------------------------------------------------------------------------------

    /**
     * Même dossier et même nom qu'une vidéo (suffixes « .fre », « .forced » ignorés) ; sinon le parser des vidéos sur le
     * nom du sous-titre (saison du nom, sinon du dossier, sinon 1 : {@link SubtitleNames#parserPath}).
     */
    public Link link(String relativePath, Lookup lookup) {
        int slash = relativePath.lastIndexOf('/');
        if (slash < 0) {
            return Link.NONE;
        }
        String dir = relativePath.substring(0, slash);
        Long same = lookup.sameName(dir, SubtitleNames.sameNameKey(relativePath.substring(slash + 1)));
        if (same != null) {
            return new Link(same, "SAME_NAME", null, null);
        }
        ParseResult r = parser.parse(SubtitleNames.parserPath(relativePath));
        if (r instanceof ParseResult.Episode e) {
            String title = Titles.normalize(e.animeTitle());
            Long id = lookup.episode(title, e.season(), e.episode());
            if (id == null) {
                // Numérotation continue d'un fichier à l'autre (« saison 3 - Episode 13 » pour le 1er épisode de la
                // saison 4) : épisode au-delà de la fin de sa saison → saison suivante, une fois.
                Integer last = lookup.lastEpisode(title, e.season());
                if (last != null && e.episode() > last) {
                    id = lookup.episode(title, e.season() + 1, e.episode() - last);
                }
            }
            return new Link(id, id == null ? null : "NAME", e.season(), e.episode());
        }
        return Link.NONE;
    }

    /** Recherche par requêtes ciblées (une correction de l'admin, pas un scan). */
    public Lookup databaseLookup(Connection c) {
        return new Lookup() {
            @Override
            public Long sameName(String dir, String stem) {
                try (PreparedStatement st = c.prepareStatement("""
                        SELECT m.file_name, e.id FROM media_file m JOIN episode e ON e.media_file_id = m.id
                        WHERE m.available AND m.relative_path LIKE ? ESCAPE '\\'""")) {
                    st.setString(1, like(dir) + "/%");
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            String name = rs.getString(1);
                            if (SubtitleNames.videoStem(name).equals(stem)) {
                                return rs.getLong(2);
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }

            @Override
            public Long episode(String normalizedTitle, int season, int episode) {
                try {
                    return episodeId(c, normalizedTitle, season, episode);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Integer lastEpisode(String normalizedTitle, int season) {
                try (PreparedStatement st = c.prepareStatement("""
                        SELECT max(e.episode_number) FROM episode e JOIN season s ON s.id = e.season_id
                        JOIN anime a ON a.id = s.anime_id WHERE a.normalized_title = ? AND s.season_number = ?""")) {
                    st.setString(1, normalizedTitle);
                    st.setInt(2, season);
                    try (ResultSet rs = st.executeQuery()) {
                        return rs.next() ? (Integer) rs.getObject(1) : null;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    public static Long episodeId(Connection c, String normalizedTitle, int season, int episode) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                WHERE a.normalized_title = ? AND s.season_number = ? AND e.episode_number = ?""")) {
            st.setString(1, normalizedTitle);
            st.setInt(2, season);
            st.setInt(3, episode);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // --- Lecture --------------------------------------------------------------------------------------------------------

    /** Sous-titres externes disponibles de l'épisode qui utilise ce fichier : complets d'abord, puis par chemin. */
    public List<External> forFile(long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT x.id, x.relative_path, x.file_size, x.last_modified, x.forced, x.language
                     FROM external_subtitle x JOIN episode e ON e.id = x.episode_id
                     WHERE e.media_file_id = ? AND x.available ORDER BY x.forced, x.relative_path""")) {
            st.setLong(1, mediaFileId);
            List<External> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new External(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, OffsetDateTime.class),
                            rs.getBoolean(5), rs.getString(6)));
                }
            }
            return out;
        }
    }

    /**
     * Empreinte des sous-titres externes d'un fichier (vide s'il n'y en a pas) : entre dans la clé de la préparation web,
     * qui est refaite quand un sous-titre est ajouté, retiré, modifié ou rattaché ailleurs.
     */
    public static String fingerprint(List<External> subs) {
        if (subs.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (External x : subs) {
            b.append(x.id()).append(':').append(x.size()).append(':').append(x.modified()).append(':').append(x.forced())
                    .append(':').append(x.language()).append(';');
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Libellé d'une piste externe, comme dans le lecteur web : « Français (externe) », « Sous-titres externes » sans
     * langue connue, « — forcés » pour les panneaux et chansons.
     */
    public static String label(String language, boolean forced) {
        String name = WebDecision.languageName(language);
        String l = name == null ? "Sous-titres externes" : name + " (externe)";
        return forced ? l + " — forcés" : l;
    }

    /** Chemin réel sous la racine de la bibliothèque (lien qui sort, « .. », fichier absent : vide). */
    public Optional<Path> resolve(String relativePath) {
        if (relativePath == null) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(config.mediaRoot()).toRealPath();
            Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root)) {
                return Optional.empty();
            }
            Path real = candidate.toRealPath();
            return real.startsWith(root) && Files.isRegularFile(real) ? Optional.of(real) : Optional.empty();
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Polices pour un sous-titre : celles de son dossier, et des sous-dossiers (deux niveaux) dont le nom parle de
     * polices (« Polices », « Fonts », « Police Spécial »…), comme on les trouve à côté des .ass. Sans suivre de lien.
     */
    public List<Path> fonts(Path subtitle) {
        List<Path> out = new ArrayList<>();
        Path dir = subtitle.getParent();
        if (dir != null) {
            collectFonts(dir, 0, out);
        }
        return out;
    }

    private static void collectFonts(Path dir, int depth, List<Path> out) {
        List<Path> subdirs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (out.size() >= MAX_FONTS) {
                    return;
                }
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && SubtitleNames.isFont(name)) {
                    out.add(p);
                } else if (depth < 2 && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && fontDir(name)) {
                    subdirs.add(p);
                }
            }
        } catch (IOException e) {
            return;
        }
        out.sort(null);
        for (Path d : subdirs) {
            collectFonts(d, depth + 1, out);
        }
    }

    static boolean fontDir(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("police") || n.contains("font") || n.contains("attachment");
    }

    /** Fin du dernier sous-titre du fichier, vide si illisible ou trop gros. */
    public static OptionalDouble lastCue(Path file) {
        try {
            if (Files.size(file) > AssTiming.MAX_BYTES) {
                return OptionalDouble.empty();
            }
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
                return AssTiming.lastCueSeconds(r);
            }
        } catch (IOException e) {
            return OptionalDouble.empty();
        }
    }
}
