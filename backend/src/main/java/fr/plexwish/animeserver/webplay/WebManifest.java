package fr.plexwish.animeserver.webplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.media.MediaRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ce que la préparation web sait d'un fichier (docs/WEB-PLAYER.md §4) : pistes (avec leur index dans le fichier),
 * sous-titres et polices jointes, et ce qui a été produit dans le cache (copie HLS, fichiers de sous-titres). Écrit en
 * base (colonne {@code manifest}) dès l'analyse, complété à chaque étape. Ne contient ni chemin ni nom de fichier
 * d'origine.
 *
 * @param direct lisible tel quel par un navigateur qui décode ses pistes : MP4 / WebM, une piste audio au plus
 * @param wantsHls une copie HLS sans ré-encodage est utile (vidéo décodable par certains navigateurs, conteneur ou
 *                 pistes audio non lisibles tels quels)
 * @param hls la copie HLS est faite (dossier du cache : {@code s_0} vidéo, {@code s_<rendition>} audio)
 */
public record WebManifest(Double durationSeconds, String container, Video video, List<Audio> audio, List<Subtitle> subtitles,
                          List<Font> fonts, boolean direct, boolean wantsHls, boolean hls) {

    /**
     * {@code index} : index de la piste dans le fichier (pour {@code -map 0:index}) ; {@code start} : instant de sa
     * première image dans le fichier (s) : la copie HLS lue par hls.js commence à 0, les sous-titres extraits gardent
     * l'horloge du fichier (décalage à appliquer : {@code -start}).
     */
    public record Video(int index, String codec, Integer bitDepth, Integer width, Integer height, Double start) {
    }

    /**
     * {@code n} : rang parmi les pistes audio ; {@code rendition} : numéro de la piste dans la copie HLS ({@code s_N}),
     * null si elle n'y est pas (codec à convertir).
     */
    public record Audio(int n, int index, String codec, Integer channels, String language, String title, boolean isDefault,
                        Integer rendition) {
    }

    /**
     * {@code kind} : {@code ass} (ASS/SSA : rendu par JASSUB, WebVTT en secours), {@code text} (SRT, WebVTT… : WebVTT),
     * {@code image} (PGS, VobSub : non affichable dans un navigateur), {@code other}. {@code ass} / {@code vtt} :
     * fichiers extraits ({@code sub_<n>.ass|vtt}), null tant qu'ils ne le sont pas.
     */
    public record Subtitle(int n, int index, String codec, String language, String title, boolean isDefault, boolean forced,
                           String kind, String ass, String vtt) {
        public Subtitle withFiles(String assFile, String vttFile) {
            return new Subtitle(n, index, codec, language, title, isDefault, forced, kind, assFile, vttFile);
        }
    }

    /** Police jointe au fichier (pièce jointe MKV), extraite sous {@code file} ({@code font_<n>.ttf|otf}). */
    public record Font(int n, int index, String file) {
    }

    /** Codecs vidéo qu'au moins un navigateur peut décoder (sinon : conversion, 10.3). */
    static boolean videoForSomeBrowser(Video v) {
        if (v == null || v.codec() == null) {
            return false;
        }
        return switch (v.codec()) {
            case "h264" -> v.bitDepth() == null || v.bitDepth() <= 8;
            case "hevc", "vp9", "av1" -> true;
            default -> false;
        };
    }

    /** Codecs audio copiés tels quels dans la copie HLS (fMP4) ; les autres (DTS, TrueHD, Vorbis…) : conversion. */
    static final Set<String> COPYABLE_AUDIO = Set.of("aac", "mp3", "opus", "flac", "ac3", "eac3");
    static final Set<String> ASS = Set.of("ass", "ssa");
    static final Set<String> TEXT = Set.of("subrip", "srt", "webvtt", "mov_text", "text");
    static final Set<String> IMAGE = Set.of("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub", "dvb_teletext");
    static final Set<String> FONT_EXT = Set.of("ttf", "otf", "ttc");
    static final int MAX_SUBTITLES = 20;
    static final int MAX_FONTS = 60;

    public boolean hasAss() {
        return subtitles.stream().anyMatch(s -> "ass".equals(s.kind()));
    }

    public WebManifest withProduced(List<Subtitle> subs, List<Font> fontList, boolean hlsDone) {
        return new WebManifest(durationSeconds, container, video, audio, subs, fontList, direct, wantsHls, hlsDone);
    }

    // --- Analyse (ffprobe -show_format -show_streams -of json) --------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Plan de préparation à partir de la sortie ffprobe ; {@code extension} : secours pour le conteneur. */
    public static WebManifest plan(String ffprobeJson, String extension) {
        JsonNode root;
        try {
            root = JSON.readTree(ffprobeJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("sortie ffprobe illisible");
        }
        if (root == null || !root.has("format")) {
            throw new IllegalArgumentException("sortie ffprobe sans format");
        }
        JsonNode format = root.path("format");
        Double duration = number(format.path("duration"));
        String container = MediaRules.container(text(format.path("format_name")), extension);
        Video video = null;
        List<Audio> audio = new ArrayList<>();
        List<Subtitle> subs = new ArrayList<>();
        List<int[]> fontCandidates = new ArrayList<>(); // {index, otf ? 1 : 0}
        int a = 0;
        int s = 0;
        for (JsonNode st : root.path("streams")) {
            int index = st.path("index").asInt(-1);
            if (index < 0) {
                continue;
            }
            String type = text(st.path("codec_type"));
            String codec = text(st.path("codec_name"));
            JsonNode disp = st.path("disposition");
            JsonNode tags = st.path("tags");
            String lang = language(text(tags.path("language")));
            String title = title(text(tags.path("title")));
            if ("video".equals(type)) {
                if (disp.path("attached_pic").asInt(0) == 1 || video != null) {
                    continue;
                }
                String pix = text(st.path("pix_fmt"));
                video = new Video(index, codec, bitDepth(st, pix), integer(st.path("width")), integer(st.path("height")),
                        signed(st.path("start_time")));
            } else if ("audio".equals(type)) {
                audio.add(new Audio(a++, index, codec, integer(st.path("channels")), lang, title, disp.path("default").asInt(0) == 1, null));
            } else if ("subtitle".equals(type) && s < MAX_SUBTITLES) {
                String kind = codec == null ? "other" : ASS.contains(codec) ? "ass" : TEXT.contains(codec) ? "text"
                        : IMAGE.contains(codec) ? "image" : "other";
                subs.add(new Subtitle(s++, index, codec, lang, title, disp.path("default").asInt(0) == 1,
                        disp.path("forced").asInt(0) == 1, kind, null, null));
            } else if ("attachment".equals(type) && fontCandidates.size() < MAX_FONTS) {
                String mime = text(tags.path("mimetype"));
                String file = text(tags.path("filename"));
                String ext = file == null || !file.contains(".") ? "" : file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                boolean font = FONT_EXT.contains(ext) || (mime != null && (mime.contains("font") || mime.contains("truetype")
                        || mime.contains("opentype") || mime.contains("sfnt")));
                if (font) {
                    boolean otf = "otf".equals(ext) || (mime != null && (mime.contains("otf") || mime.contains("opentype")));
                    fontCandidates.add(new int[]{index, otf ? 1 : 0});
                }
            }
        }
        if (duration == null) {
            for (JsonNode st : root.path("streams")) {
                Double d = number(st.path("duration"));
                if (d != null && (duration == null || d > duration)) {
                    duration = d;
                }
            }
        }
        boolean direct = ("mp4".equals(container) || "webm".equals(container)) && audio.size() <= 1;
        List<Audio> planned = new ArrayList<>();
        int rendition = 1;
        boolean anyCopy = audio.stream().anyMatch(x -> x.codec() != null && COPYABLE_AUDIO.contains(x.codec()));
        boolean wantsHls = !direct && videoForSomeBrowser(video) && (anyCopy || audio.isEmpty());
        for (Audio x : audio) {
            boolean copy = wantsHls && x.codec() != null && COPYABLE_AUDIO.contains(x.codec());
            planned.add(new Audio(x.n(), x.index(), x.codec(), x.channels(), x.language(), x.title(), x.isDefault(),
                    copy ? rendition++ : null));
        }
        boolean ass = subs.stream().anyMatch(x -> "ass".equals(x.kind()));
        List<Font> fonts = new ArrayList<>();
        if (ass) {
            for (int[] f : fontCandidates) {
                fonts.add(new Font(fonts.size(), f[0], "font_" + fonts.size() + (f[1] == 1 ? ".otf" : ".ttf")));
            }
        }
        return new WebManifest(duration, container, video, List.copyOf(planned), List.copyOf(subs), List.copyOf(fonts),
                direct, wantsHls, false);
    }

    private static Integer bitDepth(JsonNode s, String pixFmt) {
        Integer bits = integer(s.path("bits_per_raw_sample"));
        if (bits != null && bits > 0) {
            return bits;
        }
        if (pixFmt == null) {
            return null;
        }
        if (pixFmt.contains("p10") || pixFmt.contains("p12")) {
            return pixFmt.contains("12") ? 12 : 10;
        }
        return 8;
    }

    private static String language(String l) {
        if (l == null) {
            return null;
        }
        String v = l.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() || v.equals("und") || !v.matches("[a-z]{2,3}(-[a-z0-9]{2,8})?") ? null : v;
    }

    /** Titre d'une piste (« Dialogues », « Panneaux »…) : texte court, sans caractère de contrôle. */
    private static String title(String t) {
        if (t == null) {
            return null;
        }
        String v = t.replaceAll("\\p{Cntrl}", " ").strip();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private static Double number(JsonNode n) {
        String t = text(n);
        if (t == null || "N/A".equals(t)) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) && d >= 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Nombre éventuellement négatif (start_time), sinon null. */
    private static Double signed(JsonNode n) {
        String t = text(n);
        if (t == null || "N/A".equals(t)) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        try {
            return Integer.parseInt(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
