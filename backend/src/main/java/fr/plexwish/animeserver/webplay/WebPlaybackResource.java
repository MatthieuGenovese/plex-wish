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

    public record PreparingInfo(String phase, int position, Double progress, long estimatedSeconds, int retryAfterSeconds,
                                String message) {
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

    // --- 1. Que lire ? ------------------------------------------------------------------------------------------------

    @GET
    @Path("/episodes/{id}/web-playback")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response playback(@PathParam("id") long episodeId, @QueryParam("caps") String rawCaps) throws SQLException {
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
        return switch (prep.request(fileId, size, modified)) {
            case WebPrepService.Ready r -> Response.ok(source(r.key(), r.manifest(), caps, fileId, fileName, userId, false, null,
                    episode, next, resume)).build();
            case WebPrepService.Preparing p -> {
                PreparingInfo info = new PreparingInfo(p.phase(), p.position(), p.progress(), p.estimatedSeconds(), p.retryAfterSeconds(),
                        message(p));
                if (p.manifest() != null) {
                    // Pistes connues : « non lisible » se dit tout de suite ; copie HLS lisible avant la fin : on la donne.
                    WebManifest planned = p.manifest().withProduced(p.manifest().subtitles(), p.manifest().fonts(), p.manifest().wantsHls());
                    WebDecision.Result d = WebDecision.decide(planned, caps);
                    if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
                        yield Response.ok(unsupported(d.reason(), p.manifest(), episode, next, resume)).build();
                    }
                    if (p.playableEarly() && d.mode() == WebDecision.Mode.HLS) {
                        yield Response.ok(source(p.key(), planned, caps, fileId, fileName, userId, true, info, episode, next, resume)).build();
                    }
                }
                yield Response.status(Response.Status.ACCEPTED).header("Retry-After", p.retryAfterSeconds())
                        .entity(new WebPlayback("PREPARING", null, null, null, null, false, null, List.of(), List.of(), List.of(),
                                false, List.of(), info, null, episode, next, resume, 0)).build();
            }
            case WebPrepService.CacheFull f -> throw new ApiException(503, "WEB_CACHE_FULL",
                    "Le serveur n'a plus de place pour préparer cet épisode pour le navigateur (les copies déjà prêtes sont en cours "
                            + "de lecture). Réessayez dans quelques minutes.");
            case WebPrepService.Failed f -> {
                // Fichier que ce navigateur ne lirait de toute façon pas : la raison utile est celle-là, pas l'échec.
                if (f.manifest() != null) {
                    WebManifest planned = f.manifest().withProduced(f.manifest().subtitles(), f.manifest().fonts(), f.manifest().wantsHls());
                    WebDecision.Result d = WebDecision.decide(planned, caps);
                    if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
                        yield Response.ok(unsupported(d.reason(), f.manifest(), episode, next, resume)).build();
                    }
                }
                throw new ApiException(409, "WEB_PREP_FAILED",
                        "Cet épisode n'a pas pu être préparé pour le navigateur (" + f.reason() + "). Regardez-le avec l'application Android.");
            }
            case WebPrepService.Unavailable u -> throw new ApiException(503, "WEB_PREP_UNAVAILABLE",
                    "La préparation pour le navigateur est indisponible sur le serveur (" + u.reason() + ").");
        };
    }

    private static String message(WebPrepService.Preparing p) {
        String what = switch (p.phase() == null ? "" : p.phase()) {
            case "SUBS" -> "Extraction des sous-titres…";
            case "HLS" -> "Préparation de la vidéo pour le navigateur…";
            case "QUEUED" -> "Préparation pour le navigateur…";
            default -> "Analyse de l'épisode…";
        };
        return p.position() > 0 ? what + " (" + p.position() + " avant lui)" : what;
    }

    private WebPlayback unsupported(String reason, WebManifest m, EpisodeInfo e, NextEpisode next, Resume resume) {
        return new WebPlayback("UNSUPPORTED", null, null, null, null, false, m == null ? null : m.durationSeconds(), List.of(),
                List.of(), List.of(), false, List.of(), null, reason, e, next, resume, 0);
    }

    /** Réponse « à lire » : original ou copie HLS, pistes proposées, sous-titres et polices signés. */
    WebPlayback source(String key, WebManifest m, Set<String> caps, long fileId, String fileName, long userId, boolean growing,
                       PreparingInfo info, EpisodeInfo e, NextEpisode next, Resume resume) {
        WebDecision.Result d = WebDecision.decide(m, caps);
        if (d.mode() == WebDecision.Mode.UNSUPPORTED) {
            return unsupported(d.reason(), m, e, next, resume);
        }
        long exp = signer.expiry();
        String base = "/api/stream/" + fileId + "/web/" + key + "/";
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
            type = VideoMediaTypes.forFileName(fileName).orElse("video/mp4");
        } else {
            String list = String.join(",", ordered.stream().map(a -> String.valueOf(a.rendition())).toList());
            String resource = list.isEmpty() ? "master.m3u8" : "master.m3u8;a=" + list;
            url = base + "master.m3u8?" + (list.isEmpty() ? "" : "a=" + list + "&") + signer.webQuery(fileId, key, resource, userId, exp);
            type = HLS_TYPE;
        }
        List<SubtitleTrack> subs = new ArrayList<>();
        Set<String> subLabels = new HashSet<>();
        boolean image = false;
        for (WebManifest.Subtitle s : m.subtitles()) {
            if ("image".equals(s.kind())) {
                image = true;
                continue;
            }
            String vtt = s.vtt() == null ? null : base + s.vtt() + "?" + signer.webQuery(fileId, key, s.vtt(), userId, exp);
            String ass = s.ass() == null ? null : base + s.ass() + "?" + signer.webQuery(fileId, key, s.ass(), userId, exp);
            if (ass == null && vtt == null) {
                continue;
            }
            String label = unique(subLabels, label(s.language(), s.title(), "Sous-titres " + (s.n() + 1), s.forced()));
            subs.add(new SubtitleTrack(subs.size(), label, s.language(), s.forced(), s.isDefault(), ass != null ? "ass" : "vtt",
                    ass != null ? ass : vtt, vtt));
        }
        List<String> fonts = m.fonts().stream()
                .map(f -> base + f.file() + "?" + signer.webQuery(fileId, key, f.file(), userId, exp)).toList();
        List<String> missing = d.missingAudio().stream()
                .map(a -> label(a.language(), a.title(), "Piste audio " + (a.n() + 1), false)).toList();
        // Copie HLS : hls.js la fait commencer à 0, les sous-titres gardent l'horloge du fichier (§ WebManifest.Video).
        double offset = d.mode() == WebDecision.Mode.HLS && m.video().start() != null ? -m.video().start() : 0;
        return new WebPlayback("READY", d.mode().name(), url, type, Instant.ofEpochSecond(exp), growing, m.durationSeconds(),
                audio, subs, fonts, image && subs.isEmpty(), missing, info, null, e, next, resume, offset);
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
