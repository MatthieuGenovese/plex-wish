package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.library.LibraryConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Analyse ffprobe des fichiers (ARCHITECTURE §22) : un fichier à la fois, seulement les nouveaux ou modifiés (taille,
 * date), état en base (reprise après arrêt), classification Android / navigateur, durée des épisodes.
 * Le chemin vient de la base et doit rester sous la racine des médias ; aucune entrée client.
 */
@ApplicationScoped
public class MediaProbeService {

    private static final Logger LOG = Logger.getLogger(MediaProbeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Sortie JSON de ffprobe plafonnée (quelques Ko en temps normal). */
    private static final int MAX_JSON = 2 * 1024 * 1024;

    @Inject
    MediaProbeRepository repository;
    @Inject
    MediaConfig config;
    @Inject
    LibraryConfig library;

    private volatile String ffprobeVersion;
    private volatile boolean versionChecked;

    /** Fichier à analyser (lu en base). */
    public record Work(long mediaFileId, String relativePath, long size, OffsetDateTime modified, String extension) {
    }

    // --- Outils ---------------------------------------------------------------------------------------------------

    /** « ffprobe version 7.1.5 … » (première ligne), null si ffprobe est absent. */
    public String ffprobeVersion() {
        if (!versionChecked) {
            ffprobeVersion = version(config.ffprobePath());
            versionChecked = true;
        }
        return ffprobeVersion;
    }

    public String version(String executable) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(executable, "-version"), java.time.Duration.ofSeconds(10), 4096, null);
            return r.ok() ? r.stdout().lines().findFirst().map(l -> l.replaceAll(" Copyright.*", "")).orElse(null) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean scanRunning() throws SQLException {
        return repository.scanRunning();
    }

    /**
     * Fichier sur le disque, vérifié : sous la racine des médias (liens symboliques résolus), fichier ordinaire.
     * Jamais un chemin venu d'un client : {@code relative} est lu en base.
     */
    public Optional<Path> resolve(String relative) {
        try {
            Path root = Path.of(library.mediaRoot()).toAbsolutePath().normalize();
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Message d'outil sans chemin du serveur (racine des médias et nom du fichier remplacés). */
    public String clean(String message, Path file) {
        if (message == null) {
            return null;
        }
        String m = message;
        if (file != null) {
            m = m.replace(file.toString(), "<fichier>");
        }
        try {
            m = m.replace(Path.of(library.mediaRoot()).toAbsolutePath().normalize().toString(), "<médias>");
        } catch (RuntimeException ignored) {
            // racine illisible : rien à remplacer
        }
        m = m.strip();
        return m.length() > 500 ? "…" + m.substring(m.length() - 500) : m;
    }

    // --- Analyse ------------------------------------------------------------------------------------------------------

    /** Prochain épisode disponible jamais analysé, ou modifié depuis (taille ou date). */
    public Optional<Work> nextPending() throws SQLException {
        return repository.nextPending();
    }

    /** Analyse un fichier et enregistre le résultat (succès ou échec avec la raison). Renvoie « OK » ou « FAILED ». */
    public String probe(Work w) throws SQLException, InterruptedException {
        Optional<Path> file = resolve(w.relativePath());
        if (file.isEmpty()) {
            saveFailure(w, "fichier introuvable ou hors de la bibliothèque");
            return "FAILED";
        }
        List<String> cmd = new ArrayList<>(ProcessRunner.lowPriorityPrefix(config.lowPriority()));
        cmd.addAll(List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-show_format", "-show_streams",
                "-of", "json", "-i", "file:" + file.get()));
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.run(cmd, config.probeTimeout(), MAX_JSON, null);
        } catch (IOException e) {
            saveFailure(w, "ffprobe introuvable ou non exécutable");
            return "FAILED";
        }
        if (r.timedOut()) {
            saveFailure(w, "délai dépassé (" + config.probeTimeout().toSeconds() + " s)");
            return "FAILED";
        }
        if (r.exitCode() != 0) {
            String why = clean(r.stderrTail(), file.get());
            saveFailure(w, "ffprobe a échoué (code " + r.exitCode() + ")" + (why == null || why.isEmpty() ? "" : " : " + why));
            return "FAILED";
        }
        ProbeFacts facts;
        try {
            facts = ProbeFacts.parse(r.stdout());
        } catch (IllegalArgumentException e) {
            saveFailure(w, e.getMessage());
            return "FAILED";
        }
        try {
            save(w, facts);
        } catch (RuntimeException e) {
            // Défaut du classement sur un fichier inattendu : enregistré comme échec, sinon la file d'analyse
            // reprendrait ce même fichier sans fin (constat 2026-10-18 : 2 875 fichiers bloqués derrière un seul).
            LOG.errorf(e, "Analyse média : classement impossible pour le fichier %d", w.mediaFileId());
            saveFailure(w, "classement impossible (erreur interne : " + e.getClass().getSimpleName() + ")");
            return "FAILED";
        }
        return "OK";
    }

    void save(Work w, ProbeFacts f) throws SQLException {
        MediaRules.Classification cl = MediaRules.classify(f, w.extension());
        repository.saveSuccess(w, new MediaProbeRepository.ProbeResult(MediaRules.container(f.formatName(), w.extension()), f, cl));
    }

    void saveFailure(Work w, String error) throws SQLException {
        LOG.infof("Analyse média : échec pour le fichier %d (%s)", w.mediaFileId(), error);
        repository.saveFailure(w, error.length() > 600 ? error.substring(0, 600) : error);
    }

    /** Durée des épisodes = durée analysée, pour tous les épisodes (après un scan, un épisode peut avoir changé de fichier). */
    public int syncDurations() throws SQLException {
        return repository.syncAllDurations();
    }

    /** Fichiers classés avec d'anciennes règles : reclassés sans nouvelle analyse (par lots). */
    public int reclassifyOutdated(int batch) throws SQLException {
        List<MediaProbeRepository.Outdated> rows = repository.outdated(MediaRules.VERSION, batch);
        for (MediaProbeRepository.Outdated r : rows) {
            MediaRules.Classification cl;
            try {
                cl = MediaRules.classify(r.facts(), r.extension());
            } catch (RuntimeException e) {
                // Même raison que dans probe() : nouvelle analyse, qui enregistrera l'échec au lieu de boucler.
                LOG.errorf(e, "Analyse média : reclassement impossible pour le fichier %d, nouvelle analyse", r.mediaFileId());
                repository.delete(r.mediaFileId());
                continue;
            }
            repository.updateClassification(r.mediaFileId(), cl, MediaRules.VERSION);
        }
        return rows.size();
    }

    /** Admin : refaire l'analyse d'un fichier (ou de tous les échecs) au prochain passage. */
    public int requeue(Long mediaFileId) throws SQLException {
        return repository.delete(mediaFileId);
    }
}
