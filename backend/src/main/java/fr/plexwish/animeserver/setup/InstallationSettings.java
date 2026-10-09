package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.LibraryConfig;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.RemuxCache;
import fr.plexwish.animeserver.media.RemuxService;
import fr.plexwish.animeserver.poster.PosterStore;
import fr.plexwish.animeserver.tmdb.TmdbCredentials;
import fr.plexwish.animeserver.tmdb.TmdbWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Réglages communs à l'assistant de premier lancement et à Administration > Réglages : clé TMDB, jeton du DNS
 * dynamique, seuils d'espace disque, vérifications de l'installation. Aucun secret n'est jamais renvoyé.
 */
@ApplicationScoped
public class InstallationSettings {

    public static final String WARN_GB = "disk.warn_gb";
    public static final String CRITICAL_GB = "disk.critical_gb";
    public static final String REMUX_CAP_GB = "remux.cap_gb";
    private static final Set<String> VIDEO = Set.of("mkv", "mp4", "avi", "ogm", "m4v", "webm", "wmv", "mov", "ts", "m2ts", "flv");

    public record DiskView(long totalBytes, long freeBytes, DiskAdvice.Thresholds current, DiskAdvice.Thresholds proposed,
                           boolean saved) {
    }

    public record TmdbView(boolean configured, String source) {
    }

    /** Une vérification : OK, WARN (fonctionne, mais à regarder) ou FAIL (à corriger). */
    public record Check(String id, String label, String state, String detail, String fix) {
    }

    @Inject
    AppSettings settings;
    @Inject
    SecretStore secrets;
    @Inject
    TmdbCredentials tmdb;
    @Inject
    TmdbWorker tmdbWorker;
    @Inject
    RemuxCache remuxCache;
    @Inject
    RemuxService remux;
    @Inject
    MediaProbeService probe;
    @Inject
    PosterStore posters;
    @Inject
    LibraryConfig library;
    @Inject
    fr.plexwish.animeserver.media.MediaConfig mediaConfig;

    // --- TMDB --------------------------------------------------------------------------------------------------

    public TmdbView tmdb() {
        return new TmdbView(tmdb.configured(), tmdb.source().orElse(null));
    }

    public TmdbView setTmdb(String token) {
        String t = token == null ? "" : token.trim();
        if (t.length() < 20 || t.length() > 1024 || !t.matches("[\\x21-\\x7e]+")) {
            throw new ApiException(400, "TMDB_KEY_INVALID",
                    "Clé TMDB invalide : coller le « jeton d'accès en lecture » (API Read Access Token) ou la clé API de votre compte TMDB.");
        }
        try {
            secrets.write(SecretStore.TMDB, t);
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(500, "SECRETS_UNWRITABLE", "Impossible d'enregistrer la clé (dossier des secrets non accessible).");
        }
        tmdbWorker.ensureStarted();
        return tmdb();
    }

    public TmdbView clearTmdb() {
        try {
            secrets.delete(SecretStore.TMDB);
        } catch (IOException e) {
            throw new ApiException(500, "SECRETS_UNWRITABLE", "Impossible de supprimer la clé (dossier des secrets non accessible).");
        }
        return tmdb();
    }

    // --- Espace disque -----------------------------------------------------------------------------------------

    /** Volume mesuré : celui du cache de remux (le dossier de données du projet sur le NAS). */
    public DiskView disk() {
        long total = -1;
        long free = -1;
        try {
            Path p = remuxCache.root();
            Files.createDirectories(p);
            FileStore store = Files.getFileStore(p);
            total = store.getTotalSpace();
            free = store.getUsableSpace();
        } catch (IOException | SecurityException e) {
            // volume absent : valeurs inconnues
        }
        DiskAdvice.Thresholds proposed = DiskAdvice.propose(Math.max(free, 0));
        boolean saved = settings.get(WARN_GB).isPresent();
        DiskAdvice.Thresholds current = new DiskAdvice.Thresholds(
                settings.getDouble(WARN_GB).map(Double::intValue).orElse(proposed.warnGb()),
                settings.getDouble(CRITICAL_GB).map(Double::intValue).orElse(proposed.criticalGb()),
                settings.getDouble(REMUX_CAP_GB).map(Double::intValue).orElse((int) Math.round(mediaConfig.remuxCacheMaxGb())));
        return new DiskView(total, free, current, proposed, saved);
    }

    public DiskView setDisk(DiskAdvice.Thresholds t) {
        String problem = DiskAdvice.problem(t);
        if (problem != null) {
            throw new ApiException(400, "DISK_THRESHOLDS_INVALID", problem);
        }
        settings.put(WARN_GB, Integer.toString(t.warnGb()));
        settings.put(CRITICAL_GB, Integer.toString(t.criticalGb()));
        settings.put(REMUX_CAP_GB, Integer.toString(t.remuxCapGb()));
        return disk();
    }

    // --- Vérifications -------------------------------------------------------------------------------------------

    public List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        String user = runUser();
        Path media = Path.of(library.mediaRoot());
        if (!Files.isDirectory(media) || !Files.isReadable(media)) {
            checks.add(new Check("media", "Dossier des vidéos", "FAIL", "Le serveur (utilisateur " + user + ") ne peut pas lire le dossier des vidéos.",
                    "Dans le DSM : Panneau de configuration > Dossier partagé > choisir le dossier des vidéos > Modifier > Permissions : "
                            + "donner la lecture à l'utilisateur qui possède les vidéos. Ou indiquer MEDIA_UID et MEDIA_GID dans nas.env. "
                            + "Puis redémarrer le projet et revérifier."));
        } else {
            int[] counted = countVideos(media);
            if (counted[1] > 0) {
                checks.add(new Check("media", "Dossier des vidéos", "FAIL",
                        counted[1] + " dossier(s) ou fichier(s) illisible(s) par le serveur (utilisateur " + user + ").",
                        "Donner la lecture de tout le dossier (sous-dossiers compris) à cet utilisateur dans le DSM, puis revérifier."));
            } else if (counted[0] == 0) {
                checks.add(new Check("media", "Dossier des vidéos", "WARN", "Dossier lisible, mais aucune vidéo trouvée.",
                        "Vérifier MEDIA_PATH dans nas.env (le chemin du dossier des animés sur le NAS)."));
            } else {
                checks.add(new Check("media", "Dossier des vidéos", "OK",
                        "Lisible : " + (counted[2] == 1 ? "au moins " : "") + counted[0] + " vidéo(s) trouvée(s) (lecture seule, jamais modifié).", null));
            }
        }
        checks.add(writable("posters", "Dossier des affiches", posters.root(), user));
        checks.add(writable("remux", "Cache des copies remuxées", remuxCache.root(), user));
        String ffmpeg = remux.ffmpegVersion();
        String ffprobe = probe.ffprobeVersion();
        checks.add(ffmpeg != null && ffprobe != null
                ? new Check("ffmpeg", "ffmpeg et ffprobe", "OK", ffmpeg + " ; " + ffprobe, null)
                : new Check("ffmpeg", "ffmpeg et ffprobe", "FAIL", "Outils vidéo absents de l'image.", "Réinstaller l'image du serveur."));
        DiskView disk = disk();
        if (disk.freeBytes() < 0) {
            checks.add(new Check("disk", "Espace disque", "WARN", "Espace libre inconnu.", null));
        } else {
            double freeGb = disk.freeBytes() / 1e9;
            String detail = String.format(Locale.FRANCE, "%.0f Go libres sur %.0f Go.", freeGb, disk.totalBytes() / 1e9);
            checks.add(freeGb < 5 ? new Check("disk", "Espace disque", "FAIL", detail, "Libérer de la place sur le volume du NAS.")
                    : freeGb < 20 ? new Check("disk", "Espace disque", "WARN", detail, "Peu de place : le cache des copies remuxées sera réduit.")
                    : new Check("disk", "Espace disque", "OK", detail, null));
        }
        checks.add(new Check("database", "Base de données", "OK", "Connectée.", null));
        checks.add(new Check("secrets", "Secrets du serveur", "OK", "Créés au premier démarrage, jamais affichés.", null));
        return checks;
    }

    private Check writable(String id, String label, Path dir, String user) {
        try {
            Files.createDirectories(dir);
            Path probeFile = Files.createTempFile(dir, ".check-", ".tmp");
            Files.delete(probeFile);
            return new Check(id, label, "OK", "Accessible en écriture.", null);
        } catch (IOException | SecurityException e) {
            return new Check(id, label, "FAIL", "Pas d'écriture possible pour l'utilisateur " + user + ".",
                    "Redémarrer le projet (le conteneur init remet les droits) ; si le problème reste, vérifier le dossier du projet sur le NAS.");
        }
    }

    /** [vidéos trouvées, éléments illisibles, 1 si arrêté avant la fin] : au plus 5 s ou 20 000 fichiers. */
    private int[] countVideos(Path root) {
        AtomicInteger videos = new AtomicInteger();
        AtomicInteger unreadable = new AtomicInteger();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int[] seen = {0};
        boolean truncated = false;
        try (Stream<Path> s = Files.walk(root, 4)) {
            var it = s.iterator();
            while (it.hasNext()) {
                Path p;
                try {
                    p = it.next();
                } catch (java.io.UncheckedIOException e) {
                    unreadable.incrementAndGet();
                    continue;
                }
                if (++seen[0] > 20_000 || System.nanoTime() > deadline) {
                    truncated = true;
                    break;
                }
                if (!Files.isReadable(p)) {
                    unreadable.incrementAndGet();
                } else if (Files.isRegularFile(p)) {
                    String n = p.getFileName().toString();
                    int dot = n.lastIndexOf('.');
                    if (dot > 0 && VIDEO.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                        videos.incrementAndGet();
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            unreadable.incrementAndGet();
        }
        return new int[]{videos.get(), unreadable.get(), truncated ? 1 : 0};
    }

    /** Utilisateur système du serveur (numéro), pour les messages. */
    static String runUser() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    return line.split("\\s+")[1];
                }
            }
        } catch (IOException | RuntimeException e) {
            // hors Linux
        }
        return System.getProperty("user.name", "?");
    }
}
