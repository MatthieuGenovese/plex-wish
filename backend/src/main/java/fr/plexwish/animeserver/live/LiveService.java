package fr.plexwish.animeserver.live;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.ProbeFacts;
import fr.plexwish.animeserver.stream.PlaybackActivity;
import fr.plexwish.animeserver.stream.StreamSigner;
import fr.plexwish.animeserver.webplay.WebDecision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lecture à la volée (docs/LIVE-REMUX.md) : décide si elle est prête (index des images clés), écrit les playlists HLS,
 * contrôle l'accès aux ressources signées et confie la fabrication des segments à {@link LiveSessionService}.
 */
@ApplicationScoped
public class LiveService {

    /** Réponse à « je veux lire ce fichier à la volée ». */
    public sealed interface Start permits Ready, Preparing, Unavailable {
    }

    /** Playlist maîtresse signée (chemin relatif au serveur). */
    public record Ready(String masterUrl, Instant expiresAt) implements Start {
    }

    /** Relevé des images clés en cours : redemander dans quelques secondes. */
    public record Preparing() implements Start {
    }

    /** Lecture à la volée impossible pour ce fichier (raison sans chemin) : l'appelant se replie sur l'ancien mode. */
    public record Unavailable(String reason) implements Start {
    }

    @Inject
    LiveRepository repository;
    @Inject
    KeyframeIndexService keyframes;
    @Inject
    LiveSessionService sessions;
    @Inject
    MediaProbeService probe;
    @Inject
    StreamSigner signer;
    @Inject
    PlaybackActivity playback;

    public Start start(long mediaFileId, long userId) throws SQLException {
        LiveRepository.Source source = repository.source(mediaFileId).orElse(null);
        if (source == null || source.relativePath() == null) {
            return new Unavailable("fichier indisponible");
        }
        if (source.videoCodec() == null) {
            return new Unavailable("fichier pas encore analysé");
        }
        return switch (keyframes.status(source)) {
            case KeyframeIndexService.Ready r -> {
                long exp = signer.expiry();
                yield new Ready(base(mediaFileId) + "master.m3u8?" + signer.liveQuery(mediaFileId, userId, exp),
                        Instant.ofEpochSecond(exp));
            }
            case KeyframeIndexService.Computing c -> new Preparing();
            case KeyframeIndexService.Failed f -> new Unavailable(f.reason());
        };
    }

    // --- Ressources signées -------------------------------------------------------------------------------------------

    /** Signature (cible LIVE), puis utilisateur actif. */
    public void authorize(long mediaFileId, Long userId, Long exp, String sig) throws SQLException {
        if (userId == null || exp == null) {
            throw invalidUrl();
        }
        switch (signer.check(StreamSigner.Target.LIVE, mediaFileId, userId, exp, sig)) {
            case INVALID -> throw invalidUrl();
            case EXPIRED -> throw new ApiException(403, "STREAM_URL_EXPIRED", "Lien de lecture expiré : en demander un nouveau");
            case VALID -> {
            }
        }
        if (!repository.userEnabled(userId)) {
            throw new ApiException(403, "USER_DISABLED", "Compte désactivé");
        }
    }

    /** Playlist maîtresse : vidéo et groupe audio (une rendition par piste, langue, piste par défaut). */
    public String master(long mediaFileId, long userId, long exp) throws SQLException {
        Prepared p = prepared(mediaFileId);
        String q = "?" + signer.liveQuery(mediaFileId, userId, exp);
        List<ProbeFacts.Audio> audio = audioTracks(p.source());
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        int defaultIndex = 0;
        for (int i = 0; i < audio.size(); i++) {
            if (audio.get(i).isDefault()) {
                defaultIndex = i;
                break;
            }
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < audio.size(); i++) {
            ProbeFacts.Audio a = audio.get(i);
            String name = WebDecision.languageName(a.language());
            if (name == null) {
                name = "Piste audio " + (i + 1);
            }
            String unique = name;
            for (int n = 2; !names.add(unique); n++) {
                unique = name + " " + n;
            }
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"").append(unique.replace("\"", "'")).append('"');
            if (a.language() != null && a.language().matches("[a-z]{2,3}")) {
                sb.append(",LANGUAGE=\"").append(a.language()).append('"');
            }
            boolean isDefault = i == defaultIndex;
            sb.append(",DEFAULT=").append(isDefault ? "YES" : "NO").append(",AUTOSELECT=YES,URI=\"a").append(i).append(".m3u8")
                    .append(q).append("\"\n");
        }
        double duration = Math.max(1, p.plan().duration() - p.plan().start(0));
        long bandwidth = Math.max(100_000, Math.round(p.source().fileSize() * 8 / duration));
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth);
        if (!audio.isEmpty()) {
            sb.append(",AUDIO=\"aud\"");
        }
        sb.append("\nv.m3u8").append(q).append('\n');
        return sb.toString();
    }

    /** Playlist d'une piste ({@code v}, {@code a0}…) : VOD complète, durées exactes du plan. */
    public String mediaPlaylist(long mediaFileId, long userId, long exp, String track) throws SQLException {
        Prepared p = prepared(mediaFileId);
        checkTrack(p.source(), track);
        String q = "?" + signer.liveQuery(mediaFileId, userId, exp);
        LivePlan plan = p.plan();
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:")
                .append(plan.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"").append(track).append("/init.mp4").append(q).append("\"\n");
        for (int i = 0; i < plan.count(); i++) {
            sb.append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", plan.length(i)))
                    .append(track).append('/').append(i).append(".m4s").append(q).append('\n');
        }
        return sb.append("#EXT-X-ENDLIST\n").toString();
    }

    public byte[] init(long mediaFileId, long userId, String track) throws SQLException {
        Prepared p = prepared(mediaFileId);
        checkTrack(p.source(), track);
        playback.mark();
        return run(() -> sessions.init(userId, sessionSource(p), track));
    }

    public Path segment(long mediaFileId, long userId, String track, int index) throws SQLException {
        Prepared p = prepared(mediaFileId);
        checkTrack(p.source(), track);
        if (index < 0 || index >= p.plan().count()) {
            throw new ApiException(404, "LIVE_NOT_FOUND", "Ressource inconnue");
        }
        playback.mark();
        return run(() -> sessions.segment(userId, sessionSource(p), track, index));
    }

    // --- Interne --------------------------------------------------------------------------------------------------------

    private record Prepared(LiveRepository.Source source, LivePlan plan, Path file) {
    }

    /** Fichier disponible, analysé, avec un index à jour (sinon 404 : le lecteur redemande un lien). */
    private Prepared prepared(long mediaFileId) throws SQLException {
        LiveRepository.Source source = repository.source(mediaFileId).orElse(null);
        if (source == null || source.relativePath() == null || source.videoCodec() == null) {
            throw notReady();
        }
        if (!(keyframes.status(source) instanceof KeyframeIndexService.Ready r)) {
            throw notReady();
        }
        Path file = probe.resolve(source.relativePath()).orElseThrow(LiveService::notReady);
        return new Prepared(source, r.plan(), file);
    }

    private static List<ProbeFacts.Audio> audioTracks(LiveRepository.Source s) {
        return s.audio().subList(0, Math.min(LiveSessionService.MAX_AUDIO_TRACKS, s.audio().size()));
    }

    private static void checkTrack(LiveRepository.Source s, String track) {
        if (track.equals("v")) {
            return;
        }
        if (track.matches("a[0-9]{1,2}") && Integer.parseInt(track.substring(1)) < audioTracks(s).size()) {
            return;
        }
        throw new ApiException(404, "LIVE_NOT_FOUND", "Ressource inconnue");
    }

    private static LiveSessionService.Source sessionSource(Prepared p) {
        return new LiveSessionService.Source(p.source().mediaFileId(), p.file(), p.plan(), p.source().videoCodec(),
                audioTracks(p.source()));
    }

    private interface Work<T> {
        T get();
    }

    private static <T> T run(Work<T> work) {
        try {
            return work.get();
        } catch (LiveSessionService.BusyException e) {
            throw new ApiException(503, "LIVE_BUSY",
                    "Le serveur lit déjà le nombre maximal d'épisodes à la volée : réessayez dans un instant.");
        } catch (LiveSessionService.LiveException e) {
            throw new ApiException(503, "LIVE_FAILED", "Lecture impossible pour le moment (" + e.getMessage() + ").");
        }
    }

    static String base(long mediaFileId) {
        return "/api/stream/" + mediaFileId + "/live/";
    }

    private static ApiException invalidUrl() {
        return new ApiException(403, "STREAM_URL_INVALID", "Lien de lecture invalide : en demander un nouveau");
    }

    private static ApiException notReady() {
        return new ApiException(404, "LIVE_NOT_READY", "Lecture indisponible : en demander un nouveau lien");
    }
}
