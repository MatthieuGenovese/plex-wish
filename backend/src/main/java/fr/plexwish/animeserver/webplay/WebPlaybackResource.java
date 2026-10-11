package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.progress.UpNextService;
import fr.plexwish.animeserver.stream.ByteRange;
import fr.plexwish.animeserver.stream.StreamSigner;
import fr.plexwish.animeserver.stream.VideoMediaTypes;
import io.agroal.api.AgroalDataSource;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.PathPart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture dans le navigateur (phase 10, docs/WEB-PLAYER.md) :
 * <ol>
 *     <li>{@code GET /api/episodes/{id}/web-playback?caps=h264,aac,…} (Bearer) : le navigateur dit ce qu'il décode ;
 *     réponse : source à lire (original ou copie HLS) avec URL signées, pistes audio, sous-titres, polices, ou état de la
 *     préparation (202), ou « non lisible dans le navigateur » avec la raison ;</li>
 *     <li>{@code GET /api/stream/{fichier}/web/{clé}/{ressource}?u=&exp=&sig=} : playlists (réécrites avec une
 *     signature par ressource, jamais mises en cache), pistes fMP4 (Range), sous-titres, polices.</li>
 * </ol>
 * Chaque signature est liée au fichier, à la préparation et au nom de la ressource (§9, S5). Aucun chemin ni nom de
 * fichier d'origine dans les réponses.
 */
@Path("/api")
public class WebPlaybackResource {

    public record AudioTrack(int id, String label, String language, String codec, boolean isDefault) {
    }

    /** {@code format} : « ass » (JASSUB, {@code url} = ASS, {@code vttUrl} = secours) ou « vtt » ({@code url} = WebVTT). */
    public record SubtitleTrack(int id, String label, String language, boolean forced, boolean isDefault, String format,
                                String url, String vttUrl) {
    }

    /**
     * {@code conversion} : la vidéo ou le son est converti (minutes, la page peut être fermée) ; sinon simple préparation.
     * {@code resumeAt} : la lecture reprendra à cette position (s) dès que la copie en cours l'aura dépassée.
     */
    public record PreparingInfo(String phase, int position, Double progress, long estimatedSeconds, int retryAfterSeconds,
                                String message, boolean conversion, Integer resumeAt) {
    }

    public record EpisodeInfo(long id, long animeId, String animeTitle, int seasonNumber, int episodeNumber, String title,
                              Integer durationSeconds) {
    }

    public record NextEpisode(long id, int seasonNumber, int episodeNumber, String title) {
    }

    public record Resume(int positionSeconds, int durationSeconds, boolean completed) {
    }

    /**
     * {@code state} : READY (lire {@code url}), PREPARING (202, redemander après {@code preparing.retryAfterSeconds}),
     * UNSUPPORTED ({@code reason}). {@code mode} : DIRECT (original, élément vidéo natif) ou HLS (hls.js).
     * {@code growing} : copie encore en cours d'écriture (on peut lire le début).
     */
    public record WebPlayback(String state, String mode, String url, String mimeType, Instant expiresAt, boolean growing,
                              Double durationSeconds, List<AudioTrack> audio, List<SubtitleTrack> subtitles, List<String> fonts,
                              boolean imageSubtitles, List<String> unavailableAudio, PreparingInfo preparing, String reason,
                              EpisodeInfo episode, NextEpisode next, Resume resume, double subtitleOffsetSeconds) {
    }

    static final Pattern AUDIO_LIST = Pattern.compile("\\d{1,2}(,\\d{1,2}){0,19}");
    static final String HLS_TYPE = "application/vnd.apple.mpegurl";

    @Inject
    WebPrepService prep;
    @Inject
    WebCache cache;
    @Inject
    StreamSigner signer;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    UpNextService upNext;
    @Inject
    JsonWebToken jwt;
    @Inject
    fr.plexwish.animeserver.stream.PlaybackActivity playback;

    // --- 1. Que lire ? ------------------------------------------------------------------------------------------------

    @GET
    @Path("/episodes/{id}/web-playback")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response playback(@PathParam("id") long episodeId, @QueryParam("caps") String rawCaps, @QueryParam("at") Integer at)
            throws SQLException {
        long userId = Long.parseLong(jwt.getSubject());
        Set<String> caps = WebDecision.caps(rawCaps);
        long fileId;
        String fileName;
        long size;
        OffsetDateTime modified;
        EpisodeInfo episode;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT m.id, m.available, m.file_name, m.file_size, m.last_modified, a.id, a.title, s.season_number,
                            e.episode_number, e.title, e.duration_seconds
                     FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                     LEFT JOIN media_file m ON m.id = e.media_file_id WHERE e.id = ?""")) {
            st.setLong(1, episodeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable");
                }
                if (rs.getObject(1) == null || !rs.getBoolean(2)) {
                    throw unavailable();
                }
                fileId = rs.getLong(1);
                fileName = rs.getString(3);
                size = rs.getLong(4);
                modified = rs.getObject(5, OffsetDateTime.class);
                episode = new EpisodeInfo(episodeId, rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
                        (Integer) rs.getObject(11));
            }
        }
        NextEpisode next = upNext.following(episodeId)
                .map(t -> new NextEpisode(t.episodeId(), t.seasonNumber(), t.episodeNumber(), t.episodeTitle())).orElse(null);
        Resume resume = resume(userId, episodeId);
        Request q = new Request(caps, fileId, fileName, size, modified, userId, episode, next, resume, startAt(at, resume, episode));
        return switch (prep.request(fileId, size, modified)) {
            case WebPrepService.Ready r -> {
                WebDecision.Result d = WebDecision.decide(r.manifest(), caps);
                if (d.mode() == WebDecision.Mode.UNSUPPORTED && d.convert()) {
                    yield conversion(q, r.manifest(), r.key(), true);
                }
                yield Response.ok(source(r.key(), r.manifest(), r.manifest(), r.key(), q, false, null)).build();
            }
            case WebPrepService.Preparing p -> {
                PreparingInfo info = info(p, q);
                if (p.manifest() != null) {
                    // Pistes connues : « non lisible » se dit tout de suite (ou la conversion démarre en parallèle) ;
                    // copie HLS lisible avant la fin : on la donne.
                    WebManifest planned = p.manifest().withProduced(p.manifest().subtitles(), p.manifest().fonts(), p.manifest().wantsHls());
                    WebDecision.Result d = WebDecision.decide(planned, caps);
                    if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
                        yield d.convert() ? conversion(q, p.manifest(), p.key(), false)
                                : Response.ok(unsupported(d.reason(), p.manifest(), q)).build();
                    }
                    if (p.playableEarly() && covers(p, q) && d.mode() == WebDecision.Mode.HLS) {
                        yield Response.ok(source(p.key(), planned, planned, p.key(), q, true, info)).build();
                    }
                }
                yield preparing(info, q);
            }
            case WebPrepService.CacheFull f -> {
                // Fichier que ce navigateur ne lirait de toute façon pas : inutile de faire attendre.
                if (f.manifest() != null) {
                    WebManifest planned = f.manifest().withProduced(f.manifest().subtitles(), f.manifest().fonts(), f.manifest().wantsHls());
                    WebDecision.Result d = WebDecision.decide(planned, caps);
                    if (d.mode() == WebDecision.Mode.UNSUPPORTED && !d.convert()) {
                        yield Response.ok(unsupported(d.reason(), f.manifest(), q)).build();
                    }
                }
                throw cacheFull();
            }
            case WebPrepService.Failed f -> {
                // Pistes connues : la conversion peut encore servir (copie sans ré-encodage ratée) ; fichier qu'un
                // navigateur ne lira jamais : la raison utile est celle-là, pas l'échec.
                if (f.manifest() != null) {
                    WebManifest planned = f.manifest().withProduced(f.manifest().subtitles(), f.manifest().fonts(), f.manifest().wantsHls());
                    WebDecision.Result d = WebDecision.decide(planned, caps);
                    if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
                        yield d.convert() ? conversion(q, f.manifest(), null, true)
                                : Response.ok(unsupported(d.reason(), f.manifest(), q)).build();
                    }
                }
                throw new ApiException(409, "WEB_PREP_FAILED",
                        "Cet épisode n'a pas pu être préparé pour le navigateur (" + f.reason() + "). Regardez-le avec l'application Android.");
            }
            case WebPrepService.Unavailable u -> throw unavailable(u);
        };
    }

    /** Ce que la réponse doit rappeler de la demande ; {@code startAt} : où la lecture commencera (s). */
    record Request(Set<String> caps, long fileId, String fileName, long size, OffsetDateTime modified, long userId,
                   EpisodeInfo episode, NextEpisode next, Resume resume, int startAt) {
    }

    /** Avance demandée au-delà de la position de départ avant de servir une copie en cours d'écriture (s). */
    static final int RESUME_MARGIN = 12;

    /**
     * Position où la lecture commencera : {@code at} si le lecteur la donne (« Lire depuis le début » : 0, rechargement :
     * la position en cours), sinon la reprise enregistrée, avec la même règle que le lecteur (ni épisode fini, ni tout
     * début, ni les 30 dernières secondes).
     */
    static int startAt(Integer at, Resume resume, EpisodeInfo episode) {
        if (at != null) {
            return Math.max(0, Math.min(at, 86_400));
        }
        if (resume == null || resume.completed() || resume.positionSeconds() < 10) {
            return 0;
        }
        Integer d = episode.durationSeconds() != null ? episode.durationSeconds() : Integer.valueOf(resume.durationSeconds());
        return d != null && d > 0 && resume.positionSeconds() > d - 30 ? 0 : resume.positionSeconds();
    }

    /** Copie en cours d'écriture : servie seulement si elle couvre déjà la position de départ (+ 12 s). */
    static boolean covers(WebPrepService.Preparing p, Request q) {
        return q.startAt() <= 0 || p.writtenSeconds() >= q.startAt() + RESUME_MARGIN;
    }

    /**
     * Conversion pour le navigateur (10.3) : vidéo et son de la conversion, sous-titres et polices de la préparation de
     * base ({@code baseKey}, null si elle a échoué : pas de sous-titres). Lue dès qu'elle a assez d'avance.
     */
    private Response conversion(Request q, WebManifest base, String baseKey, boolean baseDone) throws SQLException {
        return switch (prep.requestConversion(q.fileId(), q.size(), q.modified(), base, WebPrepService.PRIORITY_USER)) {
            case WebPrepService.Ready r -> baseDone
                    ? Response.ok(source(r.key(), r.manifest(), base, baseKey, q, false, null)).build()
                    : preparing(new PreparingInfo("SUBS", 0, null, 5, 2, "Extraction des sous-titres…", false,
                            q.startAt() > 0 ? q.startAt() : null), q);
            case WebPrepService.Preparing p -> {
                PreparingInfo info = info(p, q);
                if (p.playableEarly() && covers(p, q) && baseDone && p.manifest() != null) {
                    WebManifest planned = p.manifest().withProduced(List.of(), List.of(), true);
                    yield Response.ok(source(p.key(), planned, base, baseKey, q, true, info)).build();
                }
                yield preparing(info, q);
            }
            case WebPrepService.Failed f -> throw new ApiException(409, "WEB_PREP_FAILED",
                    "Cet épisode n'a pas pu être converti pour le navigateur (" + f.reason() + "). Regardez-le avec l'application Android.");
            case WebPrepService.CacheFull f -> throw cacheFull();
            case WebPrepService.Unavailable u -> throw unavailable(u);
        };
    }

    private static Response preparing(PreparingInfo info, Request q) {
        return Response.status(Response.Status.ACCEPTED).header("Retry-After", info.retryAfterSeconds())
                .entity(new WebPlayback("PREPARING", null, null, null, null, false, null, List.of(), List.of(), List.of(),
                        false, List.of(), info, null, q.episode(), q.next(), q.resume(), 0)).build();
    }

    private static PreparingInfo info(WebPrepService.Preparing p, Request q) {
        return new PreparingInfo(p.phase(), p.position(), p.progress(), p.estimatedSeconds(), p.retryAfterSeconds(), message(p),
                WebPrepService.CONV.equals(p.kind()), q.startAt() > 0 ? q.startAt() : null);
    }

    private static ApiException cacheFull() {
        return new ApiException(503, "WEB_CACHE_FULL",
                "Le serveur manque de place pour préparer cet épisode pour le navigateur. Réessayez plus tard, ou regardez-le "
                        + "avec l'application Android.");
    }

    private static ApiException unavailable(WebPrepService.Unavailable u) {
        return new ApiException(503, "WEB_PREP_UNAVAILABLE",
                "La préparation pour le navigateur est indisponible sur le serveur (" + u.reason() + ").");
    }

    private static String message(WebPrepService.Preparing p) {
        boolean conv = WebPrepService.CONV.equals(p.kind());
        String what = switch (p.phase() == null ? "" : p.phase()) {
            case "SUBS" -> "Extraction des sous-titres…";
            case "HLS" -> "Préparation de la vidéo pour le navigateur…";
            case "CONVERT" -> "Conversion de la vidéo pour le navigateur…";
            case "AUDIO" -> "Conversion du son pour le navigateur…";
            case "VERIFY" -> "Vérification de la copie…";
            case "QUEUED" -> conv ? "Conversion pour le navigateur en attente…" : "Préparation pour le navigateur…";
            default -> "Analyse de l'épisode…";
        };
        if (p.paused()) {
            what += " (en pause pendant une lecture sur le serveur)";
        }
        return p.position() > 0 ? what + " (" + p.position() + " avant lui)" : what;
    }

    private WebPlayback unsupported(String reason, WebManifest m, Request q) {
        return new WebPlayback("UNSUPPORTED", null, null, null, null, false, m == null ? null : m.durationSeconds(), List.of(),
                List.of(), List.of(), false, List.of(), null, reason, q.episode(), q.next(), q.resume(), 0);
    }

    /**
     * Réponse « à lire » : original ou copie HLS ({@code key} / {@code m}), pistes proposées, sous-titres et polices
     * signés venant de la préparation de base ({@code subsKey} / {@code subsFrom} : la même, ou celle de base quand
     * {@code m} est une conversion ; null : aucun sous-titre).
     */
    WebPlayback source(String key, WebManifest m, WebManifest subsFrom, String subsKey, Request q, boolean growing, PreparingInfo info) {
        long fileId = q.fileId();
        long userId = q.userId();
        WebDecision.Result d = WebDecision.decide(m, q.caps());
        if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
            return unsupported(d.reason(), m, q);
        }
        long exp = signer.expiry();
        String base = "/api/stream/" + fileId + "/web/" + key + "/";
        String subsBase = "/api/stream/" + fileId + "/web/" + subsKey + "/";
        // Piste audio par défaut : japonais s'il existe (comme sur Android), sinon celle marquée par défaut, sinon la première.
        List<WebManifest.Audio> ordered = new ArrayList<>(d.audio());
        ordered.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        List<AudioTrack> audio = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            WebManifest.Audio a = ordered.get(i);
            audio.add(new AudioTrack(i, unique(labels, label(a.language(), a.title(), "Piste audio " + (a.n() + 1), false)),
                    a.language(), a.codec(), i == 0));
        }
        String url;
        String type;
        if (d.mode() == WebDecision.Mode.DIRECT) {
            StreamSigner.SignedUrl signed = signer.sign(fileId, userId);
            url = signed.url();
            type = VideoMediaTypes.forFileName(q.fileName()).orElse("video/mp4");
        } else {
            String list = String.join(",", ordered.stream().map(a -> String.valueOf(a.rendition())).toList());
            String resource = list.isEmpty() ? "master.m3u8" : "master.m3u8;a=" + list;
            url = base + "master.m3u8?" + (list.isEmpty() ? "" : "a=" + list + "&") + signer.webQuery(fileId, key, resource, userId, exp);
            type = HLS_TYPE;
        }
        List<SubtitleTrack> subs = new ArrayList<>();
        Set<String> subLabels = new HashSet<>();
        boolean image = false;
        List<WebManifest.Subtitle> subtitleList = subsFrom == null ? List.of() : subsFrom.subtitles();
        for (WebManifest.Subtitle s : subtitleList) {
            if ("image".equals(s.kind())) {
                image = true;
                continue;
            }
            if (subsKey == null) {
                continue;
            }
            String vtt = s.vtt() == null ? null : subsBase + s.vtt() + "?" + signer.webQuery(fileId, subsKey, s.vtt(), userId, exp);
            String ass = s.ass() == null ? null : subsBase + s.ass() + "?" + signer.webQuery(fileId, subsKey, s.ass(), userId, exp);
            if (ass == null && vtt == null) {
                continue;
            }
            String label = unique(subLabels, label(s.language(), s.title(), "Sous-titres " + (s.n() + 1), s.forced()));
            subs.add(new SubtitleTrack(subs.size(), label, s.language(), s.forced(), s.isDefault(), ass != null ? "ass" : "vtt",
                    ass != null ? ass : vtt, vtt));
        }
        List<String> fonts = subsKey == null || subsFrom == null ? List.of() : subsFrom.fonts().stream()
                .map(f -> subsBase + f.file() + "?" + signer.webQuery(fileId, subsKey, f.file(), userId, exp)).toList();
        List<String> missing = d.missingAudio().stream()
                .map(a -> label(a.language(), a.title(), "Piste audio " + (a.n() + 1), false)).toList();
        // Copie HLS : hls.js la fait commencer à 0, les sous-titres gardent l'horloge du fichier (§ WebManifest.Video).
        double offset = d.mode() == WebDecision.Mode.HLS && m.video().start() != null ? -m.video().start() : 0;
        return new WebPlayback("READY", d.mode().name(), url, type, Instant.ofEpochSecond(exp), growing, m.durationSeconds(),
                audio, subs, fonts, image && subs.isEmpty(), missing, info, null, q.episode(), q.next(), q.resume(), offset);
    }

    private static int rank(WebManifest.Audio a) {
        if ("jpn".equals(a.language()) || "ja".equals(a.language())) {
            return 0;
        }
        return a.isDefault() ? 1 : 2;
    }

    static String label(String language, String title, String fallback, boolean forced) {
        String lang = WebDecision.languageName(language);
        String l = lang == null ? (title == null ? fallback : title) : (title == null ? lang : lang + " (" + title + ")");
        return forced ? l + " — forcés" : l;
    }

    private static String unique(Set<String> seen, String label) {
        String l = label;
        for (int i = 2; !seen.add(l); i++) {
            l = label + " " + i;
        }
        return l;
    }

    private Resume resume(long userId, long episodeId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT position_seconds, duration_seconds, completed FROM playback_progress WHERE user_id = ? AND episode_id = ?")) {
            st.setLong(1, userId);
            st.setLong(2, episodeId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new Resume(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)) : null;
            }
        }
    }

    // --- 3. Pastilles « Prêt pour le navigateur » / « En préparation » (décision D9) ------------------------------------

    static final Pattern EPISODE_LIST = Pattern.compile("\\d{1,12}(,\\d{1,12}){0,99}");

    /**
     * État de la préparation pour le navigateur de quelques épisodes (fiche d'un animé, « Continuer ») : READY (lisible
     * par tous les navigateurs sans attendre) ou PREPARING ; les autres épisodes sont absents de la réponse.
     */
    @GET
    @Path("/web-status")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public java.util.Map<String, String> status(@QueryParam("episodes") String episodes) throws SQLException {
        if (episodes == null || !EPISODE_LIST.matcher(episodes).matches()) {
            throw new ApiException(400, "INVALID_EPISODES", "Liste d'épisodes invalide (100 au plus)");
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        Long[] ids = java.util.Arrays.stream(episodes.split(",")).map(Long::valueOf).toArray(Long[]::new);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT e.id, j.kind, j.status, j.priority, j.manifest FROM episode e
                     JOIN media_file f ON f.id = e.media_file_id AND f.available
                     JOIN web_job j ON j.media_file_id = f.id AND j.source_size = f.file_size
                         AND j.source_modified IS NOT DISTINCT FROM f.last_modified
                     WHERE e.id = ANY (?)""")) {
            st.setArray(1, c.createArrayOf("bigint", ids));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String ep = Long.toString(rs.getLong(1));
                    String kind = rs.getString(2);
                    String state = rs.getString(3);
                    boolean ready = "READY".equals(state) && (WebPrepService.CONV.equals(kind) || playableEverywhere(rs.getString(5)));
                    boolean preparing = "RUNNING".equals(state) || ("QUEUED".equals(state) && rs.getInt(4) < WebPrepService.PRIORITY_NIGHT);
                    if (ready) {
                        out.put(ep, "READY");
                    } else if (preparing && !"READY".equals(out.get(ep))) {
                        out.put(ep, "PREPARING");
                    }
                }
            }
        }
        return out;
    }

    private boolean playableEverywhere(String manifest) {
        WebManifest m = prep.manifest(manifest);
        return m != null && WebDecision.decide(m, WebDecision.DEFAULT).mode() != WebDecision.Mode.UNSUPPORTED;
    }

    // --- 2. Ressources signées ------------------------------------------------------------------------------------------

    @GET
    @Path("/stream/{fileId}/web/{key}/{name}")
    @PermitAll
    public Response web(@PathParam("fileId") long fileId, @PathParam("key") String key, @PathParam("name") String name,
                        @QueryParam("a") String audio, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                        @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(fileId, key, name, audio, userId, exp, sig, range, true);
    }

    @HEAD
    @Path("/stream/{fileId}/web/{key}/{name}")
    @PermitAll
    public Response webHead(@PathParam("fileId") long fileId, @PathParam("key") String key, @PathParam("name") String name,
                            @QueryParam("a") String audio, @QueryParam("u") Long userId, @QueryParam("exp") Long exp,
                            @QueryParam("sig") String sig, @HeaderParam("Range") String range) throws SQLException {
        return serve(fileId, key, name, audio, userId, exp, sig, range, false);
    }

    private Response serve(long fileId, String key, String name, String audio, Long userId, Long exp, String sig, String range,
                           boolean body) throws SQLException {
        if (userId == null || exp == null || !WebCache.validKey(key) || name == null) {
            throw invalidUrl();
        }
        boolean master = "master.m3u8".equals(name);
        if (audio != null && (!master || !AUDIO_LIST.matcher(audio).matches())) {
            throw invalidUrl();
        }
        String resource = master && audio != null ? name + ";a=" + audio : name;
        switch (signer.checkWeb(fileId, key, resource, userId, exp, sig)) {
            case INVALID -> throw invalidUrl();
            case EXPIRED -> throw new ApiException(403, "STREAM_URL_EXPIRED", "Lien de lecture expiré : en demander un nouveau");
            case VALID -> {
            }
        }
        if (!master && !WebCache.NAME.matcher(name).matches()) {
            throw notReady();
        }
        checkUserAndFile(userId, fileId);
        playback.mark();
        Optional<WebPrepService.Job> job = prep.served(fileId, key);
        if (job.isEmpty() || job.get().manifest() == null) {
            throw notReady();
        }
        boolean allowPart = "RUNNING".equals(job.get().status());
        if (master) {
            WebManifest m = job.get().manifest();
            if (!m.wantsHls()) {
                throw notReady();
            }
            String text = masterPlaylist(m, audio, fileId, key, userId, exp, job.get().sourceSize());
            return playlist(text, body);
        }
        java.nio.file.Path file = cache.file(key, name, allowPart).orElseThrow(WebPlaybackResource::notReady);
        if (name.endsWith(".m3u8")) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw notReady();
            }
            return playlist(rewriteMediaPlaylist(text, n -> "/api/stream/" + fileId + "/web/" + key + "/" + n + "?"
                    + signer.webQuery(fileId, key, n, userId, exp)), body);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw notReady();
        }
        String type = contentType(name);
        return switch (ByteRange.evaluate(range, size)) {
            case ByteRange.Full full -> Response.ok().type(type).header("Accept-Ranges", "bytes")
                    .header("Cache-Control", "private, max-age=0")
                    .entity(body ? new PathPart(file, 0, size) : null).header(HttpHeaders.CONTENT_LENGTH, size).build();
            case ByteRange.Partial(ByteRange r) -> Response.status(Response.Status.PARTIAL_CONTENT).type(type)
                    .header("Accept-Ranges", "bytes").header("Cache-Control", "private, max-age=0")
                    .entity(body ? new PathPart(file, r.start(), r.length()) : null)
                    .header("Content-Range", r.contentRange(size)).header(HttpHeaders.CONTENT_LENGTH, r.length()).build();
            case ByteRange.Unsatisfiable u -> Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Accept-Ranges", "bytes").header("Content-Range", "bytes */" + size).build();
        };
    }

    /** Playlists : contiennent des signatures, donc jamais gardées par un cache (S6). */
    private static Response playlist(String text, boolean body) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return Response.ok(body ? bytes : null).type(HLS_TYPE + "; charset=utf-8").header("Cache-Control", "no-store")
                .header(HttpHeaders.CONTENT_LENGTH, bytes.length).build();
    }

    /**
     * Playlist maîtresse écrite par le serveur : la vidéo ({@code s_0}) et les pistes audio demandées ({@code a}, dans
     * cet ordre, la première par défaut), chacune avec son URL signée.
     */
    String masterPlaylist(WebManifest m, String audio, long fileId, String key, long userId, long exp, long sourceSize) {
        List<Integer> wanted = new ArrayList<>();
        if (audio != null) {
            for (String part : audio.split(",")) {
                wanted.add(Integer.parseInt(part));
            }
        }
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        Function<String, String> url = n -> "/api/stream/" + fileId + "/web/" + key + "/" + n + "?" + signer.webQuery(fileId, key, n, userId, exp);
        Set<String> names = new HashSet<>();
        boolean first = true;
        boolean anyAudio = false;
        for (int r : wanted) {
            Optional<WebManifest.Audio> a = m.audio().stream().filter(x -> x.rendition() != null && x.rendition() == r).findFirst();
            if (a.isEmpty()) {
                continue;
            }
            String nm = unique(names, label(a.get().language(), a.get().title(), "Piste audio " + (a.get().n() + 1), false));
            String lang = WebDecision.bcp47(a.get().language());
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"").append(quoted(nm)).append('"')
                    .append(lang == null ? "" : ",LANGUAGE=\"" + lang + "\"")
                    .append(",DEFAULT=").append(first ? "YES" : "NO").append(",AUTOSELECT=").append(first ? "YES" : "NO")
                    .append(",URI=\"").append(url.apply("s_" + r + ".m3u8")).append("\"\n");
            first = false;
            anyAudio = true;
        }
        long bandwidth = m.durationSeconds() != null && m.durationSeconds() > 0
                ? Math.max(100_000, (long) (sourceSize * 8 / m.durationSeconds())) : 5_000_000;
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth);
        if (m.video() != null && m.video().width() != null && m.video().height() != null) {
            sb.append(",RESOLUTION=").append(m.video().width()).append('x').append(m.video().height());
        }
        if (anyAudio) {
            sb.append(",AUDIO=\"aud\"");
        }
        sb.append('\n').append(url.apply("s_0.m3u8")).append('\n');
        return sb.toString();
    }

    private static String quoted(String s) {
        return s.replace("\"", "'").replace("\n", " ");
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    /** Remplace chaque référence à un fichier du cache (ligne de segment, URI="…") par son URL signée. */
    static String rewriteMediaPlaylist(String text, Function<String, String> signedUrl) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("#")) {
                out.append(WebCache.NAME.matcher(t).matches() ? signedUrl.apply(t) : "#" + t).append('\n');
                continue;
            }
            Matcher mm = URI_ATTR.matcher(t);
            StringBuilder sb = new StringBuilder();
            while (mm.find()) {
                String ref = mm.group(1);
                String repl = WebCache.NAME.matcher(ref).matches() ? signedUrl.apply(ref) : ref;
                mm.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + repl + "\""));
            }
            mm.appendTail(sb);
            out.append(sb).append('\n');
        }
        return out.toString();
    }

    static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "m4s" -> name.startsWith("s_0.") ? "video/mp4" : "audio/mp4";
            case "vtt" -> "text/vtt; charset=utf-8";
            case "ass" -> "text/plain; charset=utf-8";
            case "otf" -> "font/otf";
            case "ttf" -> "font/ttf";
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private void checkUserAndFile(long userId, long fileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT (SELECT enabled FROM app_user WHERE id = ?), (SELECT available FROM media_file WHERE id = ?)""")) {
            st.setLong(1, userId);
            st.setLong(2, fileId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new ApiException(403, "USER_DISABLED", "Compte désactivé");
                }
                if (!rs.getBoolean(2)) {
                    throw unavailable();
                }
            }
        }
    }

    private static ApiException invalidUrl() {
        return new ApiException(403, "STREAM_URL_INVALID", "Lien de lecture invalide : en demander un nouveau");
    }

    private static ApiException notReady() {
        return new ApiException(404, "WEB_NOT_READY", "Copie de lecture indisponible : en demander un nouveau lien");
    }

    private static ApiException unavailable() {
        return new ApiException(404, "EPISODE_UNAVAILABLE", "Cet épisode n'est pas disponible pour le moment (fichier absent du serveur)");
    }
}
