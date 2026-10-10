package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.MediaRules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Choix de la source pour un navigateur (docs/WEB-PLAYER.md §4.1), d'après ce qu'il dit savoir décoder
 * ({@code caps}) et la préparation du fichier ({@link WebManifest}). Règles pures, testées sans serveur.
 * <ul>
 *     <li>DIRECT : l'original par Range (MP4 / WebM, une piste audio, codecs décodés) ;</li>
 *     <li>HLS : la copie HLS sans ré-encodage (pistes audio décodées seulement) ;</li>
 *     <li>UNSUPPORTED : vidéo ou son à convertir (10.3), avec la raison.</li>
 * </ul>
 */
public final class WebDecision {

    public enum Mode { DIRECT, HLS, UNSUPPORTED }

    /** {@code audio} : pistes proposées (rang {@code n} dans le fichier), dans l'ordre ; {@code missingAudio} : non lisibles. */
    public record Result(Mode mode, String reason, List<WebManifest.Audio> audio, List<WebManifest.Audio> missingAudio) {
    }

    /** Capacités qu'un navigateur peut annoncer (tout le reste est ignoré). */
    public static final Set<String> KNOWN = Set.of("h264", "hevc", "hevc10", "vp9", "av1", "aac", "mp3", "opus", "flac", "ac3", "eac3");
    /** Sans annonce : le minimum que tout navigateur lit. */
    static final Set<String> DEFAULT = Set.of("h264", "aac", "mp3");

    private WebDecision() {
    }

    /** « h264,hevc,aac » → ensemble connu (inconnus ignorés, au plus 20 éléments). */
    public static Set<String> caps(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        Set<String> out = new LinkedHashSet<>();
        String[] parts = raw.toLowerCase(Locale.ROOT).split(",", 21);
        for (int i = 0; i < Math.min(parts.length, 20); i++) {
            String p = parts[i].trim();
            if (KNOWN.contains(p)) {
                out.add(p);
            }
        }
        return out.isEmpty() ? DEFAULT : Set.copyOf(out);
    }

    /** Capacité nécessaire pour la vidéo, ou null si aucun navigateur ne la décode (H.264 10 bits, MPEG-4 ASP…). */
    static String videoCap(WebManifest.Video v) {
        if (v == null || v.codec() == null) {
            return null;
        }
        boolean deep = v.bitDepth() != null && v.bitDepth() > 8;
        return switch (v.codec()) {
            case "h264" -> deep ? null : "h264";
            case "hevc" -> deep ? "hevc10" : "hevc";
            case "vp9" -> "vp9";
            case "av1" -> "av1";
            default -> null;
        };
    }

    static String audioCap(String codec) {
        return codec != null && KNOWN.contains(codec) && !codec.startsWith("h") ? codec : null;
    }

    public static Result decide(WebManifest m, Set<String> caps) {
        if (m.video() == null) {
            return unsupported("Ce fichier n'a pas de piste vidéo lisible.");
        }
        String vcap = videoCap(m.video());
        String label = videoLabel(m.video());
        if (vcap == null || !caps.contains(vcap)) {
            return unsupported("La vidéo (" + label + ") n'est pas lisible par ce navigateur. La conversion pour le navigateur "
                    + "n'est pas encore disponible : regardez cet épisode avec l'application Android.");
        }
        List<WebManifest.Audio> ok = new ArrayList<>();
        List<WebManifest.Audio> missing = new ArrayList<>();
        for (WebManifest.Audio a : m.audio()) {
            String acap = audioCap(a.codec());
            if (acap != null && caps.contains(acap)) {
                ok.add(a);
            } else {
                missing.add(a);
            }
        }
        if (!m.audio().isEmpty() && ok.isEmpty()) {
            return unsupported("Le son (" + MediaRules.label(m.audio().get(0).codec()) + ") n'est pas lisible par ce navigateur. "
                    + "La conversion pour le navigateur n'est pas encore disponible : regardez cet épisode avec l'application Android.");
        }
        if (m.direct() && missing.isEmpty()) {
            return new Result(Mode.DIRECT, null, List.copyOf(ok), List.of());
        }
        if (!m.hls()) {
            return unsupported(m.wantsHls() ? "La copie de cet épisode pour le navigateur n'a pas pu être faite."
                    : "Ce fichier doit être converti pour le navigateur : ce n'est pas encore disponible. "
                    + "Regardez cet épisode avec l'application Android.");
        }
        List<WebManifest.Audio> inCopy = ok.stream().filter(a -> a.rendition() != null).toList();
        List<WebManifest.Audio> notInCopy = new ArrayList<>(missing);
        ok.stream().filter(a -> a.rendition() == null).forEach(notInCopy::add);
        if (!m.audio().isEmpty() && inCopy.isEmpty()) {
            return unsupported("Le son de cet épisode doit être converti pour le navigateur : ce n'est pas encore disponible. "
                    + "Regardez cet épisode avec l'application Android.");
        }
        return new Result(Mode.HLS, null, inCopy, List.copyOf(notInCopy));
    }

    private static Result unsupported(String reason) {
        return new Result(Mode.UNSUPPORTED, reason, List.of(), List.of());
    }

    static String videoLabel(WebManifest.Video v) {
        String base = MediaRules.label(v.codec());
        return v.bitDepth() != null && v.bitDepth() > 8 ? base + " " + v.bitDepth() + " bits" : base;
    }

    /** Nom français d'une langue ISO 639 (codes courants), sinon le code en majuscules. */
    public static String languageName(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "jpn", "ja" -> "Japonais";
            case "fre", "fra", "fr" -> "Français";
            case "eng", "en" -> "Anglais";
            case "ger", "deu", "de" -> "Allemand";
            case "spa", "es" -> "Espagnol";
            case "ita", "it" -> "Italien";
            case "por", "pt" -> "Portugais";
            case "kor", "ko" -> "Coréen";
            case "chi", "zho", "zh" -> "Chinois";
            case "rus", "ru" -> "Russe";
            case "ara", "ar" -> "Arabe";
            case "pol", "pl" -> "Polonais";
            case "dut", "nld", "nl" -> "Néerlandais";
            default -> code.toUpperCase(Locale.ROOT);
        };
    }

    /** Code BCP 47 court (« ja », « fr ») pour l'attribut LANGUAGE des playlists, ou null. */
    public static String bcp47(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "jpn" -> "ja";
            case "fre", "fra" -> "fr";
            case "eng" -> "en";
            case "ger", "deu" -> "de";
            case "spa" -> "es";
            case "ita" -> "it";
            case "por" -> "pt";
            case "kor" -> "ko";
            case "chi", "zho" -> "zh";
            case "rus" -> "ru";
            default -> code.length() == 2 ? code : null;
        };
    }
}
