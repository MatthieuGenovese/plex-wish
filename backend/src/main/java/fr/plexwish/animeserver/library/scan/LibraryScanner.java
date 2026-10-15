package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.library.LibraryConfig;
import fr.plexwish.animeserver.library.parse.FilenameParser;
import fr.plexwish.animeserver.library.parse.LibraryFiles;
import fr.plexwish.animeserver.library.parse.ParseResult;
import fr.plexwish.animeserver.library.parse.SubtitleNames;
import fr.plexwish.animeserver.common.db.Db;
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
import java.sql.SQLException;
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
 * {@code anime.library.batch-size} vidéos, une transaction et une poignée de requêtes multi-lignes par lot.
 * Ne modifie jamais un fichier média : il ne fait que lire le disque et écrire en base.
 */
@ApplicationScoped
public class LibraryScanner {

    private static final Logger LOG = Logger.getLogger(LibraryScanner.class);

    /** Scan arrêté avant toute écriture « disparu » (montage absent, racine illisible…). */
    public static class ScanAbortedException extends RuntimeException {
        /** {@code MEDIA_ROOT_UNAVAILABLE} ou {@code MASS_REMOVAL} (colonne scan_run.failure_code). */
        public final String code;

        public ScanAbortedException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    record Found(String path, String fileName, long size, OffsetDateTime modified) {
    }

    record Issue(Long fileId, String path, String category, String animeTitle, String detail,
                 Integer season, Integer episode, String keptPath, String seasonSource, String keptSeasonSource) {

        Issue(Long fileId, String path, String category, String animeTitle, String detail) {
            this(fileId, path, category, animeTitle, detail, null, null, null, null, null);
        }

        Issue withFileId(Long id) {
            return new Issue(id, path, category, animeTitle, detail, season, episode, keptPath, seasonSource, keptSeasonSource);
        }
    }

    record ManualOverride(String action, String animeTitle, Integer season, Integer episode) {
    }

    /** Épisode connu : id null tant que l'insertion du lot n'est pas faite. */
    static final class EpisodeRef {
        Long id;
        Long fileId;
        String filePath;
        /** Lien établi pendant CE scan : il peut encore céder la place à un fichier plus fiable. */
        boolean linkedThisScan;
        /** Fiabilité du fichier lié ce scan (0 = la meilleure, voir {@link #reliability}). */
        int reliability = Integer.MAX_VALUE;
        String seasonSource;

        EpisodeRef(Long id, Long fileId, String filePath) {
            this.id = id;
            this.fileId = fileId;
            this.filePath = filePath;
        }

        void linkThisScan(Long fileId, String path, Decision d) {
            this.fileId = fileId;
            this.filePath = path;
            this.linkedThisScan = true;
            this.reliability = reliability(d.strategy());
            this.seasonSource = d.seasonSource() == null ? null : d.seasonSource().name();
        }
    }

    /**
     * En cas de doublon au sein d'un même scan, le fichier identifié de la façon la plus fiable est gardé :
     * correction manuelle, puis SxxExx / NxEE, puis E\\d+, puis numéro seul ; l'ordre alphabétique ne
     * départage que deux fichiers de même fiabilité (ARCHITECTURE §7.5).
     */
    static int reliability(ParseResult.Strategy strategy) {
        return switch (strategy) {
            case OVERRIDE -> 0;
            case SXXEXX, NXEE -> 1;
            case E_NUMBER -> 2;
            case NUMBER_ONLY -> 3;
        };
    }

    /** Ce qu'il faut faire d'une vidéo, décidé avant d'écrire en base. */
    record Decision(Found file, String kind, String animeTitle, Integer season, Integer episode,
                    ParseResult.SeasonSource seasonSource, ParseResult.Strategy strategy) {
        Decision(Found file, String kind, String animeTitle) {
            this(file, kind, animeTitle, null, null, null, null);
        }
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
        /** Vidéos déjà traitées pendant ce scan. */
        final Set<String> processed = new HashSet<>();
    }

    @Inject
    Db db;
    @Inject
    LibraryScanRepository repository;
    @Inject
    FilenameParser parser;
    @Inject
    LibraryConfig config;
    @Inject
    SubtitleSync subtitleSync;

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
        List<Found> subtitles = new ArrayList<>();
        List<Found> videos = walk(root, stats, walkIssues, subtitles);
        if (videos.isEmpty()) {
            throw new ScanAbortedException("MEDIA_ROOT_UNAVAILABLE", "aucune vidéo trouvée dans " + root + " — montage NAS absent ?");
        }
        videos.sort(Comparator.comparing(Found::path));
        Set<String> seen = new HashSet<>();
        videos.forEach(v -> seen.add(v.path()));
        stats.videos = videos.size();
        // Horodatage commun à tous les fichiers vus : ceux qui ne l'ont pas reçu ont disparu.
        OffsetDateTime scanTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                State state = load(c);
                checkMassRemoval(state, seen, confirmMassRemoval, stats);
                int batch = Math.max(1, config.batchSize());
                for (int i = 0; i < videos.size(); i += batch) {
                    processBatch(c, videos.subList(i, Math.min(i + batch, videos.size())), seen, state, stats, runId, scanTime);
                    c.commit();
                }
                repository.insertIssues(c, runId, walkIssues);
                repository.insertIssues(c, runId, markMissing(c, scanTime, stats));
                subtitles.sort(Comparator.comparing(Found::path));
                repository.insertIssues(c, runId, subtitleSync.sync(c, root, subtitles, scanTime, stats));
                stats.animeCount = repository.countVisibleAnime(c);
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
            throw new ScanAbortedException("MEDIA_ROOT_UNAVAILABLE", root + " absent ou illisible — montage NAS absent ?");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            if (!entries.iterator().hasNext()) {
                throw new ScanAbortedException("MEDIA_ROOT_UNAVAILABLE", root + " vide — montage NAS absent ?");
            }
        } catch (IOException e) {
            throw new ScanAbortedException("MEDIA_ROOT_UNAVAILABLE", root + " illisible — montage NAS absent ? (" + e.getMessage() + ")");
        }
    }

    /**
     * Parcours récursif sans suivre aucun lien symbolique (ceux qui sortiraient de /media compris) ;
     * dossiers techniques Synology ignorés ; une erreur sur la racine interrompt le scan.
     */
    private List<Found> walk(Path root, ScanStats stats, List<Issue> issues, List<Found> subtitles) {
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
                        if (type == LibraryFiles.Type.SUBTITLE && SubtitleNames.isSubtitle(name)) {
                            subtitles.add(new Found(relative(root, file), name, attrs.size(),
                                    attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC)));
                        }
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
            throw new ScanAbortedException("MEDIA_ROOT_UNAVAILABLE", "parcours de " + root + " impossible : " + e.getMessage());
        }
        return videos;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    static String topFolder(String relativePath) {
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
            throw new ScanAbortedException("MASS_REMOVAL", disappearing + " fichiers connus sur " + known + " (" + percent
                    + " %) seraient marqués indisponibles : mauvais dossier monté ou partage NAS absent ? "
                    + "Rien n'a été modifié. Si c'est voulu, relancer le scan avec confirmMassRemoval=true.");
        }
        stats.massRemovalConfirmed = true;
        LOG.warnf("Disparition massive confirmée par l'admin : %d fichiers sur %d (%d %%)", disappearing, known, percent);
    }

    // --- Chargement des caches ---------------------------------------------------------------------

    private State load(Connection c) throws SQLException {
        State s = new State();
        s.overrides.putAll(repository.overrides(c));
        s.availablePaths.addAll(repository.availablePaths(c));
        s.animeIds.putAll(repository.animeIds(c));
        for (LibraryScanRepository.SeasonRow r : repository.seasons(c)) {
            s.seasonIds.put(r.animeId() + ":" + r.seasonNumber(), r.id());
        }
        for (LibraryScanRepository.EpisodeLink e : repository.episodes(c)) {
            String key = e.seasonId() + ":" + e.episodeNumber();
            s.episodes.put(key, new EpisodeRef(e.id(), e.fileId(), e.filePath()));
            if (e.fileId() != null) {
                s.fileToEpisode.put(e.fileId(), key);
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
            state.processed.add(d.file().path());
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
                created.linkThisScan(fileId, d.file().path(), d);
                state.episodes.put(key, created);
                state.fileToEpisode.put(fileId, key);
                inserts.add(new Object[]{key, created});
                stats.episodes++;
            } else if (fileId.equals(ref.fileId)) {
                stats.episodes++;
            } else if (ref.fileId != null && seen.contains(ref.filePath) && takesOver(d, ref, state)) {
                // Ce fichier prend la place du fichier lié, qui reste disponible (§7.5, §7.7) :
                // - lien périmé : le fichier lié ne réclame plus cet épisode (correction, renommage du parser) ;
                // - doublon apparu dans ce scan, et ce fichier est identifié de façon plus fiable ;
                // - correction manuelle face à un fichier sans correction (remplacement confirmé par l'admin).
                boolean stale = !target(d).equals(claimOf(ref.filePath, state));
                if (!stale && state.processed.contains(ref.filePath)) {
                    // Le fichier délié a déjà été compté comme épisode dans ce scan : il devient un doublon.
                    stats.duplicates++;
                    String why = d.strategy() == ParseResult.Strategy.OVERRIDE
                            ? "remplacé par la correction manuelle de " : "remplacé par ";
                    issues.add(new Issue(ref.fileId, ref.filePath, "DUPLICATE", d.animeTitle(),
                            "saison " + d.season() + ", épisode " + d.episode() + " : " + why + d.file().path()
                                    + (d.strategy() == ParseResult.Strategy.OVERRIDE ? "" : " (identification plus fiable)"),
                            d.season(), d.episode(), d.file().path(), ref.seasonSource != null ? ref.seasonSource
                                    : seasonSourceOf(ref.filePath, state), d.seasonSource().name()));
                } else {
                    stats.episodes++;
                }
                state.fileToEpisode.remove(ref.fileId);
                ref.linkThisScan(fileId, d.file().path(), d);
                state.fileToEpisode.put(fileId, key);
                if (ref.id != null) {
                    relink.put(ref.id, fileId);
                    unlink.remove(ref.id);
                }
            } else if (ref.fileId != null && seen.contains(ref.filePath)) {
                // Le fichier déjà lié est toujours là : on le garde, celui-ci est signalé (§7.5).
                stats.duplicates++;
                issues.add(new Issue(fileId, d.file().path(), "DUPLICATE", d.animeTitle(),
                        "saison " + d.season() + ", épisode " + d.episode() + " : déjà fourni par " + ref.filePath
                                + (state.overrides.containsKey(ref.filePath) ? " (correction manuelle)" : ""),
                        d.season(), d.episode(), ref.filePath, d.seasonSource().name(), seasonSourceOf(ref.filePath, state)));
            } else {
                // Ancien fichier disparu (ou détaché) : l'épisode garde son id et passe sur le nouveau (§7.9).
                if (ref.fileId != null) {
                    state.fileToEpisode.remove(ref.fileId);
                    stats.rebranched++;
                }
                ref.linkThisScan(fileId, d.file().path(), d);
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
            repository.unlinkEpisodes(c, unlink);
        }
        if (!relink.isEmpty()) {
            repository.relinkEpisodes(c, relink);
        }
        insertEpisodes(c, inserts);
        // Les problèmes détectés avant l'écriture des fichiers reçoivent maintenant leur id :
        // c'est lui que l'admin utilise pour corriger (jamais un chemin fourni par le client).
        List<Issue> withIds = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            withIds.add(issue.fileId() != null ? issue : issue.withFileId(fileIds.get(issue.path())));
        }
        repository.insertIssues(c, runId, withIds);
    }

    /**
     * Ce fichier (décision {@code d}) prend-il l'épisode déjà lié à un autre fichier toujours présent ?
     * Un épisode lié lors d'un scan précédent ne change pas de fichier, sauf si ce fichier ne le réclame plus
     * (lien périmé) ou face à une correction manuelle (le remplacement a été confirmé à sa création).
     */
    private boolean takesOver(Decision d, EpisodeRef ref, State state) {
        if (!target(d).equals(claimOf(ref.filePath, state))) {
            return true;
        }
        if (ref.linkedThisScan) {
            return reliability(d.strategy()) < ref.reliability;
        }
        return d.strategy() == ParseResult.Strategy.OVERRIDE && !state.overrides.containsKey(ref.filePath);
    }

    /** Épisode réclamé par un fichier (correction manuelle, sinon parser), ou null. Pur : ne compte rien. */
    private String claimOf(String path, State state) {
        ManualOverride o = state.overrides.get(path);
        if (o != null) {
            return "EPISODE".equals(o.action()) ? target(o.animeTitle(), o.season(), o.episode()) : null;
        }
        return parser.parse(path) instanceof ParseResult.Episode e ? target(e.animeTitle(), e.season(), e.episode()) : null;
    }

    private static String target(Decision d) {
        return target(d.animeTitle(), d.season(), d.episode());
    }

    /** Clé « épisode visé » (titre normalisé, saison, épisode), partagée avec le contrôle des corrections. */
    public static String target(String animeTitle, Integer season, Integer episode) {
        return Titles.normalize(animeTitle) + "|" + season + "|" + episode;
    }

    /** Correction manuelle, sinon parser ; comptes et problèmes du rapport. */
    private Decision decide(Found f, State state, ScanStats stats, List<Issue> issues) {
        ManualOverride o = state.overrides.get(f.path());
        if (o != null) {
            stats.overridesApplied++;
            return switch (o.action()) {
                case "EPISODE" -> new Decision(f, "EPISODE", o.animeTitle(), o.season(), o.episode(),
                        ParseResult.SeasonSource.OVERRIDE, ParseResult.Strategy.OVERRIDE);
                case "EXTRA" -> {
                    stats.extras++;
                    yield new Decision(f, "EXTRA", topFolder(f.path()));
                }
                default -> {
                    stats.ignoredByOverride++;
                    yield new Decision(f, "IGNORED", topFolder(f.path()));
                }
            };
        }
        ParseResult result = parser.parse(f.path());
        return switch (result) {
            case ParseResult.Episode e -> {
                if (e.folderSeasonConflict() != null) {
                    stats.seasonMismatches++;
                    issues.add(new Issue(null, f.path(), "SEASON_MISMATCH", e.animeTitle(),
                            "dossier : saison " + e.folderSeasonConflict() + ", nom : saison " + e.season() + " (retenue)",
                            e.season(), e.episode(), null, e.seasonSource().name(), null));
                }
                yield new Decision(f, "EPISODE", e.animeTitle(), e.season(), e.episode(), e.seasonSource(), e.strategy());
            }
            case ParseResult.Extra x -> {
                stats.extras++;
                yield new Decision(f, "EXTRA", x.animeTitle());
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
                yield new Decision(f, "UNRESOLVED", u.animeTitle());
            }
        };
    }

    /** Une requête par lot : insertion des nouveaux fichiers, mise à jour des connus (réapparus compris). */
    private Map<String, Long> upsertMediaFiles(Connection c, List<Decision> decisions, OffsetDateTime scanTime,
                                               ScanStats stats) throws SQLException {
        List<LibraryScanRepository.MediaFileRow> rows = new ArrayList<>(decisions.size());
        for (Decision d : decisions) {
            Found f = d.file();
            rows.add(new LibraryScanRepository.MediaFileRow(f.path(), f.fileName(), f.size(), f.modified(),
                    LibraryFiles.extension(f.fileName()), d.kind()));
        }
        Map<String, Long> ids = new HashMap<>();
        for (LibraryScanRepository.UpsertedFile u : repository.upsertMediaFiles(c, rows, scanTime)) {
            ids.put(u.path(), u.id());
            if (u.inserted()) {
                stats.newFiles++;
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
            state.animeIds.putAll(repository.insertAnimes(c, newTitles));
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
            List<LibraryScanRepository.SeasonRow> rows = new ArrayList<>();
            for (String key : newSeasons) {
                String[] parts = key.split(":");
                rows.add(new LibraryScanRepository.SeasonRow(0, Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
            }
            for (LibraryScanRepository.SeasonRow r : repository.insertSeasons(c, rows)) {
                state.seasonIds.put(r.animeId() + ":" + r.seasonNumber(), r.id());
            }
        }
    }

    private void insertEpisodes(Connection c, List<Object[]> inserts) throws SQLException {
        if (inserts.isEmpty()) {
            return;
        }
        List<LibraryScanRepository.NewEpisode> rows = new ArrayList<>();
        Map<String, EpisodeRef> byKey = new HashMap<>();
        for (Object[] row : inserts) {
            String key = (String) row[0];
            EpisodeRef ref = (EpisodeRef) row[1];
            String[] parts = key.split(":");
            rows.add(new LibraryScanRepository.NewEpisode(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), ref.fileId));
            byKey.put(key, ref);
        }
        for (LibraryScanRepository.InsertedEpisode e : repository.insertEpisodes(c, rows)) {
            byKey.get(e.seasonId() + ":" + e.episodeNumber()).id = e.id();
        }
    }

    /** Fichiers connus absents de ce scan : indisponibles, jamais supprimés (§7.9). Une seule requête. */
    private List<Issue> markMissing(Connection c, OffsetDateTime scanTime, ScanStats stats) throws SQLException {
        List<Issue> issues = new ArrayList<>();
        for (LibraryScanRepository.MissingFile m : repository.markMissing(c, scanTime)) {
            issues.add(new Issue(m.id(), m.path(), "MISSING", topFolder(m.path()), "fichier disparu depuis le scan précédent"));
        }
        stats.missing = issues.size();
        if (!issues.isEmpty()) {
            LOG.infof("%d fichier(s) disparu(s), marqué(s) indisponible(s)", issues.size());
        }
        return issues;
    }

    /** Origine de la saison d'un fichier déjà lié (correction manuelle, sinon parser : pur calcul). */
    private String seasonSourceOf(String path, State state) {
        if (state.overrides.containsKey(path)) {
            return ParseResult.SeasonSource.OVERRIDE.name();
        }
        return parser.parse(path) instanceof ParseResult.Episode e ? e.seasonSource().name() : null;
    }
}
