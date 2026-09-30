package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.library.LibraryConfig;
import fr.plexwish.animeserver.library.parse.FilenameParser;
import fr.plexwish.animeserver.library.parse.LibraryFiles;
import fr.plexwish.animeserver.library.parse.ParseResult;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scan de la bibliothèque (ARCHITECTURE §7) : parcours du disque, puis traitement par lots de
 * {@code library.batch-size} vidéos, une transaction et une poignée de requêtes multi-lignes par lot.
 * Ne modifie jamais un fichier média : il ne fait que lire le disque et écrire en base.
 */
@ApplicationScoped
public class LibraryScanner {

    private static final Logger LOG = Logger.getLogger(LibraryScanner.class);

    /** Scan arrêté avant toute écriture « disparu » (montage absent, racine illisible…). */
    public static class ScanAbortedException extends RuntimeException {
        public ScanAbortedException(String message) {
            super(message);
        }
    }

    record Found(String path, String fileName, long size, OffsetDateTime modified) {
    }

    record Issue(Long fileId, String path, String category, String animeTitle, String detail) {
    }

    record ManualOverride(String action, String animeTitle, Integer season, Integer episode) {
    }

    /** Épisode connu : id null tant que l'insertion du lot n'est pas faite. */
    static final class EpisodeRef {
        Long id;
        Long fileId;
        String filePath;

        EpisodeRef(Long id, Long fileId, String filePath) {
            this.id = id;
            this.fileId = fileId;
            this.filePath = filePath;
        }
    }

    /** Ce qu'il faut faire d'une vidéo, décidé avant d'écrire en base. */
    private record Decision(Found file, String kind, String animeTitle, Integer season, Integer episode) {
    }

    /** Caches chargés une fois au début du scan (quelques milliers de lignes). */
    static final class State {
        final Map<String, ManualOverride> overrides = new HashMap<>();
        final Map<String, Long> animeIds = new HashMap<>();
        final Map<String, Long> seasonIds = new HashMap<>();
        final Map<String, EpisodeRef> episodes = new HashMap<>();
        final Map<Long, String> fileToEpisode = new HashMap<>();
        /** Fichiers disponibles avant ce scan : sert au garde-fou de disparition massive. */
        final Set<String> availablePaths = new HashSet<>();
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    FilenameParser parser;
    @Inject
    LibraryConfig config;

    /**
     * @param confirmMassRemoval l'admin a confirmé qu'une disparition de plus de la moitié des fichiers
     *                           connus est normale (sinon le scan s'arrête avant d'écrire quoi que ce soit).
     */
    public ScanStats scan(long runId, boolean confirmMassRemoval) throws SQLException {
        long start = System.nanoTime();
        Path root = Path.of(config.mediaRoot());
        checkMediaRoot(root);

        ScanStats stats = new ScanStats();
        List<Issue> walkIssues = new ArrayList<>();
        List<Found> videos = walk(root, stats, walkIssues);
        if (videos.isEmpty()) {
            throw new ScanAbortedException("aucune vidéo trouvée dans " + root + " — montage NAS absent ?");
        }
        videos.sort(Comparator.comparing(Found::path));
        Set<String> seen = new HashSet<>();
        videos.forEach(v -> seen.add(v.path()));
        stats.videos = videos.size();
        // Horodatage commun à tous les fichiers vus : ceux qui ne l'ont pas reçu ont disparu.
        OffsetDateTime scanTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                State state = load(c);
                checkMassRemoval(state, seen, confirmMassRemoval, stats);
                int batch = Math.max(1, config.batchSize());
                for (int i = 0; i < videos.size(); i += batch) {
                    processBatch(c, videos.subList(i, Math.min(i + batch, videos.size())), seen, state, stats, runId, scanTime);
                    c.commit();
                }
                insertIssues(c, runId, walkIssues);
                insertIssues(c, runId, markMissing(c, scanTime, stats));
                stats.animeCount = countVisibleAnime(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        stats.durationMs = (System.nanoTime() - start) / 1_000_000;
        return stats;
    }

    // --- Garde-fous et parcours du disque ----------------------------------------------------------

    /** /media doit exister, être lisible et non vide ; sinon rien n'est touché en base (§7.1). */
    static void checkMediaRoot(Path root) {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new ScanAbortedException(root + " absent ou illisible — montage NAS absent ?");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            if (!entries.iterator().hasNext()) {
                throw new ScanAbortedException(root + " vide — montage NAS absent ?");
            }
        } catch (IOException e) {
            throw new ScanAbortedException(root + " illisible — montage NAS absent ? (" + e.getMessage() + ")");
        }
    }

    /**
     * Parcours récursif sans suivre aucun lien symbolique (ceux qui sortiraient de /media compris) ;
     * dossiers techniques Synology ignorés ; une erreur sur la racine interrompt le scan.
     */
    private List<Found> walk(Path root, ScanStats stats, List<Issue> issues) {
        List<Found> videos = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && LibraryFiles.isIgnoredDirectory(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        stats.symlinksSkipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    LibraryFiles.Type type = LibraryFiles.typeOf(name);
                    if (type == LibraryFiles.Type.VIDEO) {
                        videos.add(new Found(relative(root, file), name, attrs.size(),
                                attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC)));
                    } else {
                        stats.countOther(type.name().toLowerCase());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if (file.equals(root)) {
                        throw e;
                    }
                    stats.unreadable++;
                    String rel = relative(root, file);
                    issues.add(new Issue(null, rel, "UNREADABLE", topFolder(rel), e.getClass().getSimpleName()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ScanAbortedException("parcours de " + root + " impossible : " + e.getMessage());
        }
        return videos;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static String topFolder(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? null : relativePath.substring(0, slash);
    }

    /**
     * Garde-fou de disparition massive : si ce scan rendrait indisponibles plus de la moitié des fichiers
     * connus (mauvais dossier monté, sous-dossier non monté…), il s'arrête AVANT toute écriture,
     * sauf confirmation explicite de l'admin. Appelé avant le premier lot.
     */
    static void checkMassRemoval(State state, Set<String> seen, boolean confirmed, ScanStats stats) {
        int known = state.availablePaths.size();
        long disappearing = state.availablePaths.stream().filter(p -> !seen.contains(p)).count();
        stats.knownFiles = known;
        if (known == 0 || disappearing * 2 <= known) {
            return;
        }
        long percent = Math.round(100.0 * disappearing / known);
        if (!confirmed) {
            throw new ScanAbortedException(disappearing + " fichiers connus sur " + known + " (" + percent
                    + " %) seraient marqués indisponibles : mauvais dossier monté ou partage NAS absent ? "
                    + "Rien n'a été modifié. Si c'est voulu, relancer le scan avec confirmMassRemoval=true.");
        }
        stats.massRemovalConfirmed = true;
        LOG.warnf("Disparition massive confirmée par l'admin : %d fichiers sur %d (%d %%)", disappearing, known, percent);
    }

    // --- Chargement des caches ---------------------------------------------------------------------

    private State load(Connection c) throws SQLException {
        State s = new State();
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT relative_path, action, anime_title, season_number, episode_number FROM media_file_override")) {
                while (rs.next()) {
                    s.overrides.put(rs.getString(1), new ManualOverride(rs.getString(2), rs.getString(3),
                            (Integer) rs.getObject(4), (Integer) rs.getObject(5)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT relative_path FROM media_file WHERE available")) {
                while (rs.next()) {
                    s.availablePaths.add(rs.getString(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, normalized_title FROM anime")) {
                while (rs.next()) {
                    s.animeIds.put(rs.getString(2), rs.getLong(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, anime_id, season_number FROM season")) {
                while (rs.next()) {
                    s.seasonIds.put(rs.getLong(2) + ":" + rs.getInt(3), rs.getLong(1));
                }
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT e.id, e.season_id, e.episode_number, e.media_file_id, m.relative_path
                    FROM episode e LEFT JOIN media_file m ON m.id = e.media_file_id""")) {
                while (rs.next()) {
                    String key = rs.getLong(2) + ":" + rs.getInt(3);
                    Long fileId = (Long) rs.getObject(4);
                    s.episodes.put(key, new EpisodeRef(rs.getLong(1), fileId, rs.getString(5)));
                    if (fileId != null) {
                        s.fileToEpisode.put(fileId, key);
                    }
                }
            }
        }
        return s;
    }

    // --- Un lot ------------------------------------------------------------------------------------

    private void processBatch(Connection c, List<Found> files, Set<String> seen, State state, ScanStats stats,
                              long runId, OffsetDateTime scanTime) throws SQLException {
        List<Issue> issues = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>(files.size());
        for (Found f : files) {
            decisions.add(decide(f, state, stats, issues));
        }

        Map<String, Long> fileIds = upsertMediaFiles(c, decisions, scanTime, stats);
        ensureAnimeAndSeasons(c, decisions, state);

        List<Long> unlink = new ArrayList<>();
        Map<Long, Long> relink = new LinkedHashMap<>();
        List<Object[]> inserts = new ArrayList<>();
        for (Decision d : decisions) {
            Long fileId = fileIds.get(d.file().path());
            String current = state.fileToEpisode.get(fileId);
            String key = "EPISODE".equals(d.kind())
                    ? state.seasonIds.get(state.animeIds.get(Titles.normalize(d.animeTitle())) + ":" + d.season()) + ":" + d.episode()
                    : null;
            if (current != null && !current.equals(key)) {
                // Le fichier n'est plus cet épisode (correction manuelle, extra…) : on le détache.
                EpisodeRef old = state.episodes.get(current);
                unlink.add(old.id);
                old.fileId = null;
                old.filePath = null;
                state.fileToEpisode.remove(fileId);
            }
            if (key == null) {
                continue;
            }
            EpisodeRef ref = state.episodes.get(key);
            if (ref == null) {
                EpisodeRef created = new EpisodeRef(null, fileId, d.file().path());
                state.episodes.put(key, created);
                state.fileToEpisode.put(fileId, key);
                inserts.add(new Object[]{key, created});
                stats.episodes++;
            } else if (fileId.equals(ref.fileId)) {
                stats.episodes++;
            } else if (ref.fileId != null && seen.contains(ref.filePath)) {
                // Le fichier déjà lié est toujours là : on le garde, celui-ci est signalé (§7.5).
                stats.duplicates++;
                issues.add(new Issue(fileId, d.file().path(), "DUPLICATE", d.animeTitle(),
                        "saison " + d.season() + ", épisode " + d.episode() + " : déjà fourni par " + ref.filePath));
            } else {
                // Ancien fichier disparu (ou détaché) : l'épisode garde son id et passe sur le nouveau (§7.9).
                if (ref.fileId != null) {
                    state.fileToEpisode.remove(ref.fileId);
                    stats.rebranched++;
                }
                ref.fileId = fileId;
                ref.filePath = d.file().path();
                state.fileToEpisode.put(fileId, key);
                if (ref.id == null) {
                    // Épisode créé dans ce lot puis détaché : son insertion portera le bon fichier.
                    stats.episodes++;
                    continue;
                }
                relink.put(ref.id, fileId);
                unlink.remove(ref.id);
                stats.episodes++;
            }
        }

        if (!unlink.isEmpty()) {
            try (PreparedStatement st = c.prepareStatement("UPDATE episode SET media_file_id = NULL WHERE id = ANY (?)")) {
                st.setArray(1, c.createArrayOf("bigint", unlink.toArray()));
                st.executeUpdate();
            }
        }
        if (!relink.isEmpty()) {
            List<Object> params = new ArrayList<>();
            relink.forEach((episodeId, fileId) -> {
                params.add(episodeId);
                params.add(fileId);
            });
            String sql = "UPDATE episode e SET media_file_id = v.file_id FROM (VALUES "
                    + Sql.values(relink.size(), "?::bigint", "?::bigint") + ") AS v(id, file_id) WHERE e.id = v.id";
            try (PreparedStatement st = Sql.prepare(c, sql, params)) {
                st.executeUpdate();
            }
        }
        insertEpisodes(c, inserts);
        // Les problèmes détectés avant l'écriture des fichiers reçoivent maintenant leur id :
        // c'est lui que l'admin utilise pour corriger (jamais un chemin fourni par le client).
        List<Issue> withIds = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            withIds.add(issue.fileId() != null ? issue
                    : new Issue(fileIds.get(issue.path()), issue.path(), issue.category(), issue.animeTitle(), issue.detail()));
        }
        insertIssues(c, runId, withIds);
    }

    /** Correction manuelle, sinon parser ; comptes et problèmes du rapport. */
    private Decision decide(Found f, State state, ScanStats stats, List<Issue> issues) {
        ManualOverride o = state.overrides.get(f.path());
        if (o != null) {
            stats.overridesApplied++;
            return switch (o.action()) {
                case "EPISODE" -> new Decision(f, "EPISODE", o.animeTitle(), o.season(), o.episode());
                case "EXTRA" -> {
                    stats.extras++;
                    yield new Decision(f, "EXTRA", topFolder(f.path()), null, null);
                }
                default -> {
                    stats.ignoredByOverride++;
                    yield new Decision(f, "IGNORED", topFolder(f.path()), null, null);
                }
            };
        }
        ParseResult result = parser.parse(f.path());
        return switch (result) {
            case ParseResult.Episode e -> {
                if (e.folderSeasonConflict() != null) {
                    stats.seasonMismatches++;
                    issues.add(new Issue(null, f.path(), "SEASON_MISMATCH", e.animeTitle(),
                            "dossier : saison " + e.folderSeasonConflict() + ", nom : saison " + e.season() + " (retenue)"));
                }
                yield new Decision(f, "EPISODE", e.animeTitle(), e.season(), e.episode());
            }
            case ParseResult.Extra x -> {
                stats.extras++;
                yield new Decision(f, "EXTRA", x.animeTitle(), null, null);
            }
            case ParseResult.Unresolved u -> {
                String category = switch (u.problem()) {
                    case MULTI_EPISODE -> {
                        stats.multiEpisodes++;
                        yield "MULTI_EPISODE";
                    }
                    case DECIMAL_EPISODE -> {
                        stats.decimalEpisodes++;
                        yield "DECIMAL_EPISODE";
                    }
                    default -> {
                        stats.unresolved++;
                        yield "UNRESOLVED";
                    }
                };
                issues.add(new Issue(null, f.path(), category, u.animeTitle(), u.detail()));
                yield new Decision(f, "UNRESOLVED", u.animeTitle(), null, null);
            }
        };
    }

    /** Une requête par lot : insertion des nouveaux fichiers, mise à jour des connus (réapparus compris). */
    private Map<String, Long> upsertMediaFiles(Connection c, List<Decision> decisions, OffsetDateTime scanTime,
                                               ScanStats stats) throws SQLException {
        List<Object> params = new ArrayList<>(decisions.size() * 8);
        for (Decision d : decisions) {
            Found f = d.file();
            params.add(f.path());
            params.add(f.fileName());
            params.add(f.size());
            params.add(f.modified());
            params.add(LibraryFiles.extension(f.fileName()));
            params.add(d.kind());
            params.add(scanTime);
            params.add(scanTime);
        }
        String sql = "INSERT INTO media_file (relative_path, file_name, file_size, last_modified, container, kind, "
                + "first_seen_at, last_seen_at) VALUES "
                + Sql.values(decisions.size(), "?", "?", "?", "?", "?", "?", "?", "?")
                + " ON CONFLICT (relative_path) DO UPDATE SET file_name = EXCLUDED.file_name, "
                + "file_size = EXCLUDED.file_size, last_modified = EXCLUDED.last_modified, container = EXCLUDED.container, "
                + "kind = EXCLUDED.kind, available = TRUE, missing_since = NULL, last_seen_at = EXCLUDED.last_seen_at "
                + "RETURNING id, relative_path, (xmax = 0) AS inserted";
        Map<String, Long> ids = new HashMap<>();
        try (PreparedStatement st = Sql.prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                ids.put(rs.getString(2), rs.getLong(1));
                if (rs.getBoolean(3)) {
                    stats.newFiles++;
                }
            }
        }
        return ids;
    }

    /** Crée en une requête chacun les animés et saisons encore inconnus de ce lot. */
    private void ensureAnimeAndSeasons(Connection c, List<Decision> decisions, State state) throws SQLException {
        Map<String, String> newTitles = new LinkedHashMap<>();
        for (Decision d : decisions) {
            if ("EPISODE".equals(d.kind())) {
                String norm = Titles.normalize(d.animeTitle());
                if (!state.animeIds.containsKey(norm)) {
                    newTitles.putIfAbsent(norm, d.animeTitle().trim());
                }
            }
        }
        if (!newTitles.isEmpty()) {
            List<Object> params = new ArrayList<>();
            newTitles.forEach((norm, title) -> {
                params.add(title);
                params.add(norm);
            });
            String sql = "INSERT INTO anime (title, normalized_title) VALUES " + Sql.values(newTitles.size(), "?", "?")
                    + " ON CONFLICT (normalized_title) DO UPDATE SET normalized_title = EXCLUDED.normalized_title"
                    + " RETURNING id, normalized_title";
            try (PreparedStatement st = Sql.prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    state.animeIds.put(rs.getString(2), rs.getLong(1));
                }
            }
        }
        Set<String> newSeasons = new LinkedHashSet<>();
        for (Decision d : decisions) {
            if ("EPISODE".equals(d.kind())) {
                String key = state.animeIds.get(Titles.normalize(d.animeTitle())) + ":" + d.season();
                if (!state.seasonIds.containsKey(key)) {
                    newSeasons.add(key);
                }
            }
        }
        if (!newSeasons.isEmpty()) {
            List<Object> params = new ArrayList<>();
            for (String key : newSeasons) {
                String[] parts = key.split(":");
                params.add(Long.parseLong(parts[0]));
                params.add(Integer.parseInt(parts[1]));
            }
            String sql = "INSERT INTO season (anime_id, season_number) VALUES " + Sql.values(newSeasons.size(), "?", "?")
                    + " ON CONFLICT (anime_id, season_number) DO UPDATE SET season_number = EXCLUDED.season_number"
                    + " RETURNING id, anime_id, season_number";
            try (PreparedStatement st = Sql.prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    state.seasonIds.put(rs.getLong(2) + ":" + rs.getInt(3), rs.getLong(1));
                }
            }
        }
    }

    private void insertEpisodes(Connection c, List<Object[]> inserts) throws SQLException {
        if (inserts.isEmpty()) {
            return;
        }
        List<Object> params = new ArrayList<>();
        Map<String, EpisodeRef> byKey = new HashMap<>();
        for (Object[] row : inserts) {
            String key = (String) row[0];
            EpisodeRef ref = (EpisodeRef) row[1];
            String[] parts = key.split(":");
            params.add(Long.parseLong(parts[0]));
            params.add(Integer.parseInt(parts[1]));
            params.add(ref.fileId);
            byKey.put(key, ref);
        }
        String sql = "INSERT INTO episode (season_id, episode_number, media_file_id) VALUES "
                + Sql.values(inserts.size(), "?", "?", "?") + " RETURNING id, season_id, episode_number";
        try (PreparedStatement st = Sql.prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                byKey.get(rs.getLong(2) + ":" + rs.getInt(3)).id = rs.getLong(1);
            }
        }
    }

    /** Fichiers connus absents de ce scan : indisponibles, jamais supprimés (§7.9). Une seule requête. */
    private List<Issue> markMissing(Connection c, OffsetDateTime scanTime, ScanStats stats) throws SQLException {
        List<Issue> issues = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("""
                UPDATE media_file SET available = FALSE, missing_since = now()
                WHERE available AND last_seen_at < ? RETURNING id, relative_path""")) {
            st.setObject(1, scanTime);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString(2);
                    issues.add(new Issue(rs.getLong(1), path, "MISSING", topFolder(path), "fichier disparu depuis le scan précédent"));
                }
            }
        }
        stats.missing = issues.size();
        if (!issues.isEmpty()) {
            LOG.infof("%d fichier(s) disparu(s), marqué(s) indisponible(s)", issues.size());
        }
        return issues;
    }

    private void insertIssues(Connection c, long runId, List<Issue> issues) throws SQLException {
        for (int i = 0; i < issues.size(); i += 1000) {
            List<Issue> chunk = issues.subList(i, Math.min(i + 1000, issues.size()));
            List<Object> params = new ArrayList<>(chunk.size() * 6);
            for (Issue issue : chunk) {
                params.add(runId);
                params.add(issue.fileId());
                params.add(issue.path());
                params.add(issue.category());
                params.add(issue.animeTitle());
                params.add(issue.detail());
            }
            String sql = "INSERT INTO scan_issue (scan_run_id, media_file_id, relative_path, category, anime_title, detail) VALUES "
                    + Sql.values(chunk.size(), "?", "?::bigint", "?", "?", "?", "?");
            try (PreparedStatement st = Sql.prepare(c, sql, params)) {
                st.executeUpdate();
            }
        }
    }

    private int countVisibleAnime(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT count(DISTINCT s.anime_id) FROM episode e
                JOIN season s ON s.id = e.season_id JOIN media_file m ON m.id = e.media_file_id
                WHERE m.available""")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
