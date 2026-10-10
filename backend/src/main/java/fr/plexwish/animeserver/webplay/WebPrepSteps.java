package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.ProcessRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Étapes de la préparation web après l'analyse (docs/WEB-PLAYER.md §4.2), sans aucun ré-encodage audio ni vidéo :
 * <ol>
 *     <li>{@code SUBS} : sous-titres texte extraits ({@code sub_N.ass} pour JASSUB, {@code sub_N.vtt} pour tous) et
 *     polices jointes ({@code font_N.ttf|otf}, si le fichier a de l'ASS), noms fixés par le serveur ;</li>
 *     <li>{@code HLS} : copie HLS fMP4 à fichier unique par piste ({@code s_0} vidéo, {@code s_N} audio), playlists
 *     « EVENT » lisibles pendant l'écriture ; HEVC marqué {@code hvc1} (sinon Chrome refuse la piste) ;</li>
 *     <li>{@code VERIFY} : playlists terminées, durée proche de l'original.</li>
 * </ol>
 * ffmpeg sans shell, priorité basse, délai maximal ; chemins venus de la base et du cache seulement.
 */
@ApplicationScoped
public class WebPrepSteps {

    private static final Logger LOG = Logger.getLogger(WebPrepSteps.class);
    private static final long MB = 1_000_000L;

    /** Avancement : {@code phase} (null = inchangée) et fraction 0..1 de l'ensemble. */
    @FunctionalInterface
    public interface Progress {
        void update(String phase, double value);
    }

    @Inject
    MediaConfig config;
    @Inject
    MediaProbeService probe;

    /** Produit les fichiers dans {@code part} et renvoie le manifeste complété. */
    public WebManifest run(Path source, Path part, WebManifest m, Progress progress)
            throws WebPrepService.StepFailure, InterruptedException {
        boolean anySubs = m.subtitles().stream().anyMatch(s -> "ass".equals(s.kind()) || "text".equals(s.kind()));
        double subsShare = m.wantsHls() ? 0.15 : 0.95;
        List<WebManifest.Subtitle> subs = m.subtitles();
        List<WebManifest.Font> fonts = List.of();
        if (anySubs) {
            progress.update("SUBS", 0);
            Extracted e = extractSubtitles(source, part, m, f -> progress.update(null, f * subsShare));
            subs = e.subtitles();
            fonts = e.fonts();
        }
        boolean hls = false;
        if (m.wantsHls()) {
            progress.update("HLS", subsShare);
            copyHls(source, part, m, f -> progress.update(null, subsShare + f * (0.97 - subsShare)));
            progress.update("VERIFY", 0.97);
            verifyHls(part, m);
            hls = true;
        }
        return m.withProduced(subs, fonts, hls);
    }

    // --- Sous-titres et polices ---------------------------------------------------------------------------------------

    record Extracted(List<WebManifest.Subtitle> subtitles, List<WebManifest.Font> fonts) {
    }

    Extracted extractSubtitles(Path source, Path part, WebManifest m, DoubleConsumer progress)
            throws WebPrepService.StepFailure, InterruptedException {
        List<String> cmd = base();
        for (WebManifest.Font f : m.fonts()) {
            cmd.addAll(List.of("-dump_attachment:" + f.index(), part.resolve(f.file()).toString()));
        }
        cmd.addAll(List.of("-i", "file:" + source));
        for (WebManifest.Subtitle s : m.subtitles()) {
            cmd.addAll(outputs(s, part));
        }
        ProcessRunner.Result r = run(cmd, source, progress, m.durationSeconds());
        if (!r.ok()) {
            // Une piste fautive ne doit pas priver des autres : nouvel essai piste par piste.
            LOG.infof("Préparation web : extraction groupée des sous-titres en échec (%s), essai piste par piste", reason(r, source));
            for (WebManifest.Subtitle s : m.subtitles()) {
                List<String> one = outputs(s, part);
                if (one.isEmpty()) {
                    continue;
                }
                List<String> c = base();
                c.addAll(List.of("-i", "file:" + source));
                c.addAll(one);
                run(c, source, x -> { }, m.durationSeconds());
            }
        }
        List<WebManifest.Subtitle> subs = new ArrayList<>();
        for (WebManifest.Subtitle s : m.subtitles()) {
            String ass = "ass".equals(s.kind()) && nonEmpty(part.resolve("sub_" + s.n() + ".ass")) ? "sub_" + s.n() + ".ass" : null;
            String vtt = ("ass".equals(s.kind()) || "text".equals(s.kind())) && nonEmpty(part.resolve("sub_" + s.n() + ".vtt"))
                    ? "sub_" + s.n() + ".vtt" : null;
            subs.add(s.withFiles(ass, vtt));
        }
        List<WebManifest.Font> fonts = new ArrayList<>();
        long total = 0;
        for (WebManifest.Font f : m.fonts()) {
            Path p = part.resolve(f.file());
            long size = size(p);
            if (size <= 0 || size > config.webFontMaxMb() * MB || total + size > config.webFontsTotalMaxMb() * MB) {
                delete(p);
                continue;
            }
            total += size;
            fonts.add(f);
        }
        return new Extracted(List.copyOf(subs), List.copyOf(fonts));
    }

    /** Sorties d'une piste : ASS d'origine (copie) et WebVTT (texte, styles perdus) ; rien pour les images. */
    private static List<String> outputs(WebManifest.Subtitle s, Path part) {
        List<String> out = new ArrayList<>();
        if ("ass".equals(s.kind())) {
            out.addAll(List.of("-map", "0:" + s.index(), "-c:s", "ssa".equals(s.codec()) ? "ass" : "copy", "-f", "ass",
                    "file:" + part.resolve("sub_" + s.n() + ".ass")));
        }
        if ("ass".equals(s.kind()) || "text".equals(s.kind())) {
            out.addAll(List.of("-map", "0:" + s.index(), "-c:s", "webvtt", "-f", "webvtt",
                    "file:" + part.resolve("sub_" + s.n() + ".vtt")));
        }
        return out;
    }

    // --- Copie HLS ----------------------------------------------------------------------------------------------------

    /** Arguments de la copie : vidéo puis pistes audio copiées, dans l'ordre de leur numéro dans la copie. */
    List<String> hlsCommand(Path source, Path part, WebManifest m) {
        List<String> cmd = base();
        cmd.addAll(List.of("-i", "file:" + source, "-map", "0:" + m.video().index()));
        List<WebManifest.Audio> renditions = m.audio().stream().filter(a -> a.rendition() != null)
                .sorted((x, y) -> Integer.compare(x.rendition(), y.rendition())).toList();
        for (WebManifest.Audio a : renditions) {
            cmd.addAll(List.of("-map", "0:" + a.index()));
        }
        cmd.addAll(List.of("-c", "copy"));
        if ("hevc".equals(m.video().codec())) {
            cmd.addAll(List.of("-tag:v", "hvc1")); // sinon « hev1 » : refusé par Chrome
        }
        cmd.addAll(List.of("-f", "hls", "-hls_segment_type", "fmp4", "-hls_flags", "single_file+independent_segments",
                "-hls_playlist_type", "event", "-hls_time", "6", "-hls_list_size", "0"));
        if (renditions.isEmpty()) {
            cmd.add(part.resolve("s_0.m3u8").toString());
        } else {
            StringBuilder map = new StringBuilder("v:0");
            for (int i = 0; i < renditions.size(); i++) {
                map.append(" a:").append(i);
            }
            cmd.addAll(List.of("-var_stream_map", map.toString(), part.resolve("s_%v.m3u8").toString()));
        }
        return cmd;
    }

    void copyHls(Path source, Path part, WebManifest m, DoubleConsumer progress) throws WebPrepService.StepFailure, InterruptedException {
        ProcessRunner.Result r = run(hlsCommand(source, part, m), source, progress, m.durationSeconds());
        if (!r.ok()) {
            throw new WebPrepService.StepFailure("copie pour le navigateur impossible : " + reason(r, source));
        }
    }

    /** Playlists terminées, fichiers présents, durée proche de l'original (1 %, 2 s au moins). */
    void verifyHls(Path part, WebManifest m) throws WebPrepService.StepFailure {
        List<Integer> ids = new ArrayList<>(List.of(0));
        m.audio().stream().filter(a -> a.rendition() != null).forEach(a -> ids.add(a.rendition()));
        for (int id : ids) {
            Path pl = part.resolve("s_" + id + ".m3u8");
            String text;
            try {
                text = Files.readString(pl, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new WebPrepService.StepFailure("copie incomplète (playlist " + id + " absente)");
            }
            if (!text.contains("#EXT-X-ENDLIST") || !nonEmpty(part.resolve("s_" + id + ".m4s"))) {
                throw new WebPrepService.StepFailure("copie incomplète (piste " + id + ")");
            }
            double d = duration(text);
            Double src = m.durationSeconds();
            if (id == 0 && src != null && src > 0 && Math.abs(d - src) > Math.max(2.0, src * 0.01)) {
                throw new WebPrepService.StepFailure(String.format(Locale.ROOT, "copie incorrecte (durée %.0f s au lieu de %.0f s)", d, src));
            }
        }
    }

    /** Copie en cours lisible : au moins deux segments vidéo et deux segments par piste audio déjà écrits. */
    public boolean playableEarly(Path part, WebManifest m) {
        if (!m.wantsHls()) {
            return false;
        }
        List<Integer> ids = new ArrayList<>(List.of(0));
        m.audio().stream().filter(a -> a.rendition() != null).forEach(a -> ids.add(a.rendition()));
        for (int id : ids) {
            try {
                Path pl = part.resolve("s_" + id + ".m3u8");
                if (!Files.isRegularFile(pl, LinkOption.NOFOLLOW_LINKS) || segments(Files.readString(pl, StandardCharsets.UTF_8)) < 2) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    static int segments(String playlist) {
        int n = 0;
        for (String line : playlist.split("\\R")) {
            if (line.startsWith("#EXTINF:")) {
                n++;
            }
        }
        return n;
    }

    static double duration(String playlist) {
        double total = 0;
        for (String line : playlist.split("\\R")) {
            if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int comma = v.indexOf(',');
                try {
                    total += Double.parseDouble(comma < 0 ? v : v.substring(0, comma));
                } catch (NumberFormatException ignored) {
                    // ligne invalide : ignorée
                }
            }
        }
        return total;
    }

    // --- Outils -------------------------------------------------------------------------------------------------------

    private List<String> base() {
        List<String> cmd = new ArrayList<>(ProcessRunner.lowPriorityPrefix(config.lowPriority()));
        cmd.addAll(List.of(config.ffmpegPath(), "-nostdin", "-hide_banner", "-v", "warning", "-progress", "pipe:1", "-nostats", "-y"));
        return cmd;
    }

    private ProcessRunner.Result run(List<String> cmd, Path source, DoubleConsumer progress, Double duration)
            throws WebPrepService.StepFailure, InterruptedException {
        try {
            Duration timeout = config.webPrepTimeout();
            return ProcessRunner.run(cmd, timeout, 64 * 1024, null, line -> {
                if (line.startsWith("out_time_us=") && duration != null && duration > 0) {
                    try {
                        progress.accept(Math.max(0, Math.min(0.99, Long.parseLong(line.substring(12)) / 1e6 / duration)));
                    } catch (NumberFormatException ignored) {
                        // « N/A » au début
                    }
                }
            });
        } catch (IOException e) {
            throw new WebPrepService.StepFailure("ffmpeg introuvable ou non exécutable");
        }
    }

    private String reason(ProcessRunner.Result r, Path source) {
        if (r.timedOut()) {
            return "délai dépassé (" + config.webPrepTimeout().toMinutes() + " min)";
        }
        String msg = probe.clean(r.stderrTail(), source);
        if (msg == null || msg.isBlank()) {
            return "code " + r.exitCode();
        }
        String[] lines = msg.strip().split("\\R");
        return "code " + r.exitCode() + " : " + lines[lines.length - 1];
    }

    private static boolean nonEmpty(Path p) {
        return size(p) > 0;
    }

    private static long size(Path p) {
        try {
            return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.debugf("Préparation web : %s non effacé", p.getFileName());
        }
    }
}
