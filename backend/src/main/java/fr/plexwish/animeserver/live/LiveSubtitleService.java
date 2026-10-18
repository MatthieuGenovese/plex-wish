package fr.plexwish.animeserver.live;

import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.ProbeFacts;
import fr.plexwish.animeserver.media.ProcessRunner;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Sous-titres texte intégrés au fichier, pour une lecture à la volée sur Android (docs/LIVE-REMUX.md §7) : le HLS
 * ne les transporte pas, ils sont donc extraits à part (ASS gardé en ASS, les autres en WebVTT) et donnés au lecteur
 * comme des sous-titres externes. Petits fichiers gardés dans {@code <remux-cache>/live-subs/} (nommés d'après le
 * fichier, sa taille et sa date : une source modifiée donne un autre nom), effacés après 30 jours sans lecture.
 * Les sous-titres en image (PGS, VobSub) ne sont pas transmis.
 */
@ApplicationScoped
public class LiveSubtitleService {

    private static final Logger LOG = Logger.getLogger(LiveSubtitleService.class);
    static final Set<String> ASS = Set.of("ass", "ssa");
    static final Set<String> TEXT = Set.of("subrip", "srt", "webvtt", "mov_text", "text");
    static final Duration KEEP = Duration.ofDays(30);
    static final String SSA_MIME = "text/x-ssa";
    static final String VTT_MIME = "text/vtt";

    /** Piste texte du fichier : rang parmi les pistes de sous-titres ({@code 0:s:N}), format de sortie, MIME. */
    public record Track(int index, String extension, String mimeType, String language, boolean forced, boolean isDefault) {
    }

    @Inject
    MediaConfig config;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        Path dir = root();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant limit = Instant.now().minus(KEEP);
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                try {
                    if (f.getFileName().toString().endsWith(".part") || Files.getLastModifiedTime(f).toInstant().isBefore(limit)) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException ignored) {
                    // réessayé au prochain démarrage
                }
            });
        } catch (IOException ignored) {
            // dossier illisible : rien à nettoyer
        }
    }

    Path root() {
        return Path.of(config.remuxCachePath()).resolve("live-subs");
    }

    /** Pistes texte transmissibles, dans l'ordre des pistes de sous-titres du fichier. */
    public static List<Track> textTracks(List<ProbeFacts.Subtitle> subtitles) {
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < subtitles.size(); i++) {
            ProbeFacts.Subtitle s = subtitles.get(i);
            if (s.codec() == null) {
                continue;
            }
            if (ASS.contains(s.codec())) {
                out.add(new Track(i, "ass", SSA_MIME, s.language(), s.forced(), s.isDefault()));
            } else if (TEXT.contains(s.codec())) {
                out.add(new Track(i, "vtt", VTT_MIME, s.language(), s.forced(), s.isDefault()));
            }
        }
        return out;
    }

    /** Fichier extrait (fait à la première demande, gardé ensuite). */
    public Path extract(long mediaFileId, Path source, long size, OffsetDateTime modified, Track track, String codec) {
        long stamp = modified == null ? 0 : modified.toEpochSecond();
        Path out = root().resolve(mediaFileId + "-" + size + "-" + stamp + "-" + track.index() + "." + track.extension());
        synchronized (locks.computeIfAbsent(out.toString(), k -> new Object())) {
            try {
                if (Files.exists(out)) {
                    Files.setLastModifiedTime(out, FileTime.from(Instant.now()));
                    return out;
                }
                Files.createDirectories(out.getParent());
                Path part = out.resolveSibling(out.getFileName() + ".part");
                List<String> cmd = new ArrayList<>(List.of(config.ffmpegPath(), "-nostdin", "-hide_banner", "-v", "error", "-y",
                        "-i", "file:" + source, "-map", "0:s:" + track.index()));
                if (track.extension().equals("ass")) {
                    cmd.addAll(List.of("-c:s", "ass".equals(codec) ? "copy" : "ass", "-f", "ass"));
                } else {
                    cmd.addAll(List.of("-c:s", "webvtt", "-f", "webvtt"));
                }
                cmd.add("file:" + part);
                ProcessRunner.Result r = ProcessRunner.run(cmd, config.liveIndexTimeout(), 64 * 1024, null);
                if (!r.ok() || !Files.exists(part)) {
                    Files.deleteIfExists(part);
                    LOG.infof("Lecture à la volée : extraction des sous-titres %d du fichier %d impossible (code %d)",
                            track.index(), mediaFileId, r.exitCode());
                    throw new LiveSessionService.LiveException("sous-titres illisibles");
                }
                Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return out;
            } catch (IOException e) {
                throw new LiveSessionService.LiveException("sous-titres indisponibles");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LiveSessionService.LiveException("interrompu");
            } finally {
                locks.remove(out.toString());
            }
        }
    }
}
