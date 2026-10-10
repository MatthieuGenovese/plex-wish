package fr.plexwish.animeserver.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classification d'un fichier analysé (ARCHITECTURE §22.3). Règles explicites, regroupées ici pour être modifiées
 * facilement ; changer une règle = augmenter {@link #VERSION} : les fichiers déjà analysés sont reclassés sans
 * nouvelle analyse.
 *
 * <p>Android (Media3 sur le téléphone) : DIRECT (lu tel quel), REMUX (conteneur non lu ou mal lu, contenu lisible :
 * AVI, OGM → MKV sans ré-encodage, phase 9.2), TRANSCODE (codec que le téléphone ne décode pas : seul un
 * ré-encodage aiderait, hors périmètre). Navigateur (lecteur web, phase 10) : lisible ou non, avec les raisons.
 */
public final class MediaRules {

    /** À augmenter à chaque changement de règle. */
    public static final int VERSION = 3;

    public enum Android { DIRECT, REMUX, TRANSCODE }

    public record Classification(Android android, List<String> androidReasons, boolean browserPlayable, List<String> browserReasons) {
    }

    // --- Android ----------------------------------------------------------------------------------------------

    /** Conteneurs lus correctement par Media3. */
    static final Set<String> ANDROID_CONTAINERS = Set.of("matroska", "mp4", "webm", "mpegts", "flv");
    /** Conteneurs à remuxer en MKV (constat du 2026-10-06 : AVI mal horodaté, OGM vidéo non lu). */
    static final Set<String> ANDROID_REMUX_CONTAINERS = Set.of("avi", "ogg");
    /** Codecs vidéo décodés par les téléphones visés (le 10 bits HEVC dépend du téléphone : le S24 le décode). */
    static final Set<String> ANDROID_VIDEO = Set.of("h264", "hevc", "mpeg4", "vp8", "vp9", "av1", "h263");
    /** Codecs audio lus par Media3 sans extension (AC3/E-AC3/DTS : seulement si le téléphone a le décodeur). */
    static final Set<String> ANDROID_AUDIO = Set.of("aac", "mp3", "mp2", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le",
            "alac", "ac3", "eac3");
    static final Set<String> ANDROID_AUDIO_DEPENDS = Set.of("ac3", "eac3");

    // --- Navigateur ---------------------------------------------------------------------------------------------

    /** Vidéo et son lus par les navigateurs dans la copie HLS (H.264 8 bits ; HEVC : selon le navigateur). */
    static final Set<String> BROWSER_VIDEO = Set.of("h264", "vp9", "av1");
    static final Set<String> BROWSER_AUDIO = Set.of("aac", "mp3", "opus", "flac");
    /** Sous-titres en image : jamais affichés par un navigateur (le texte, ASS compris, est extrait par le serveur). */
    static final Set<String> BROWSER_IMAGE_SUBTITLES = Set.of("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub");

    private MediaRules() {
    }

    /** Conteneur normalisé à partir du nom de démultiplexeur ffmpeg (et de l'extension en secours). */
    public static String container(String formatName, String extension) {
        String f = formatName == null ? "" : formatName.toLowerCase(Locale.ROOT);
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (f.startsWith("matroska")) {
            return "webm".equals(ext) ? "webm" : "matroska";
        }
        if (f.startsWith("mov,mp4")) {
            return "mp4";
        }
        if (f.isEmpty()) {
            return ext;
        }
        return f.split(",")[0];
    }

    public static Classification classify(ProbeFacts p, String extension) {
        String container = container(p.formatName(), extension);
        ProbeFacts.Video v = p.video();
        List<String> reasons = new ArrayList<>();
        Android android;
        if (v == null) {
            android = Android.TRANSCODE;
            reasons.add("aucune piste vidéo");
        } else if (!ANDROID_VIDEO.contains(v.codec())) {
            android = Android.TRANSCODE;
            reasons.add("codec vidéo " + label(v.codec()) + " non décodé par Android");
        } else if (ANDROID_REMUX_CONTAINERS.contains(container)) {
            android = Android.REMUX;
            reasons.add("ogg".equals(container) ? "conteneur OGM non lu par Android" : "conteneur AVI mal lu par Android (horodatage du son)");
        } else if (ANDROID_CONTAINERS.contains(container)) {
            android = Android.DIRECT;
        } else {
            android = Android.REMUX;
            reasons.add("conteneur " + container.toUpperCase(Locale.ROOT) + " non garanti sur Android");
        }
        if (android != Android.TRANSCODE) {
            if (!p.audio().isEmpty() && p.audio().stream().noneMatch(a -> ANDROID_AUDIO.contains(a.codec()))) {
                android = Android.TRANSCODE;
                reasons.add("son " + label(p.audio().get(0).codec()) + " non lu par Android");
            } else if (p.audio().stream().allMatch(a -> ANDROID_AUDIO_DEPENDS.contains(a.codec())) && !p.audio().isEmpty()) {
                reasons.add("son " + label(p.audio().get(0).codec()) + " : dépend du téléphone");
            }
            if (v != null && "hevc".equals(v.codec()) && v.bitDepth() != null && v.bitDepth() >= 10) {
                reasons.add("HEVC 10 bits : décodé par les téléphones récents (S24 : oui)");
            }
            if (v != null && "h264".equals(v.codec()) && v.bitDepth() != null && v.bitDepth() >= 10) {
                // Aucun décodeur matériel de téléphone ne lit le H.264 10 bits : l'app passe au décodeur FFmpeg (v2).
                reasons.add("H.264 10 bits : lisible sur Android (décodage logiciel)");
            }
        }

        // Navigateur (lecteur web, phase 10, docs/WEB-PLAYER.md) : le conteneur ne compte plus (copie HLS sans
        // ré-encodage faite par le serveur) ; seuls les codecs à convertir et les sous-titres en image en empêchent la
        // lecture. HEVC : selon le navigateur et sa carte graphique (lu par Chrome / Edge avec décodeur matériel).
        List<String> browser = new ArrayList<>();
        if (v == null) {
            browser.add("aucune piste vidéo");
        } else if ("hevc".equals(v.codec())) {
            browser.add((v.bitDepth() != null && v.bitDepth() >= 10 ? "HEVC 10 bits" : "HEVC") + " : selon le navigateur");
        } else if ("h264".equals(v.codec()) && v.bitDepth() != null && v.bitDepth() >= 10) {
            browser.add("H.264 10 bits : à convertir pour le navigateur");
        } else if ("mpeg4".equals(v.codec())) {
            browser.add("MPEG-4 ASP (Xvid/DivX) : à convertir pour le navigateur");
        } else if (!BROWSER_VIDEO.contains(v.codec())) {
            browser.add("vidéo " + label(v.codec()) + " : à convertir pour le navigateur");
        }
        if (!p.audio().isEmpty() && p.audio().stream().noneMatch(a -> BROWSER_AUDIO.contains(a.codec()))) {
            browser.add("son " + label(p.audio().get(0).codec()) + " : à convertir pour le navigateur");
        }
        if (!p.subtitles().isEmpty() && p.subtitles().stream().allMatch(s -> BROWSER_IMAGE_SUBTITLES.contains(s.codec()))) {
            browser.add("sous-titres en image (" + String.join(", ", p.subtitles().stream().map(ProbeFacts.Subtitle::codec)
                    .distinct().map(MediaRules::label).toList()) + ") : non affichables dans un navigateur");
        }
        return new Classification(android, List.copyOf(reasons), browser.isEmpty(), List.copyOf(browser));
    }

    /** Nom lisible d'un codec ffmpeg. */
    public static String label(String codec) {
        if (codec == null) {
            return "inconnu";
        }
        return switch (codec) {
            case "h264" -> "H.264";
            case "hevc" -> "HEVC";
            case "mpeg4" -> "MPEG-4 ASP";
            case "msmpeg4v3", "msmpeg4v2", "msmpeg4v1" -> "DivX 3 (MS-MPEG4)";
            case "ass", "ssa" -> "ASS";
            case "subrip" -> "SRT";
            case "dvd_subtitle" -> "VobSub";
            case "hdmv_pgs_subtitle" -> "PGS";
            case "dts" -> "DTS";
            case "truehd" -> "TrueHD";
            case "ac3" -> "AC3";
            case "eac3" -> "E-AC3";
            case "mp3" -> "MP3";
            case "aac" -> "AAC";
            case "vorbis" -> "Vorbis";
            case "wmv3" -> "WMV";
            case "wmav2" -> "WMA";
            default -> codec;
        };
    }
}
