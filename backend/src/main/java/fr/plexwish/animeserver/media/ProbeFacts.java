package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ce que ffprobe dit d'un fichier (en-têtes seulement). {@code formatName} : nom du démultiplexeur ffmpeg
 * (« avi », « ogg », « matroska,webm », « mov,mp4,m4a,3gp,3g2,mj2 »…).
 */
public record ProbeFacts(Double durationSeconds, String formatName, Video video, List<Audio> audio, List<Subtitle> subtitles) {

    public record Video(String codec, String profile, Integer bitDepth, Integer width, Integer height, String pixFmt) {
    }

    public record Audio(String codec, String profile, Integer channels, String language, boolean isDefault) {
    }

    public record Subtitle(String codec, String language, boolean isDefault, boolean forced) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lecture de la sortie {@code ffprobe -show_format -show_streams -of json}. */
    public static ProbeFacts parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("sortie ffprobe illisible");
        }
        if (root == null || !root.has("format")) {
            throw new IllegalArgumentException("sortie ffprobe sans format");
        }
        JsonNode format = root.path("format");
        Double duration = number(format.path("duration"));
        Video video = null;
        List<Audio> audio = new ArrayList<>();
        List<Subtitle> subs = new ArrayList<>();
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText("");
            String codec = text(s.path("codec_name"));
            JsonNode disp = s.path("disposition");
            String lang = language(text(s.path("tags").path("language")));
            switch (type) {
                case "video" -> {
                    // Images jointes (pochette, police en image) : pas la vidéo de l'épisode.
                    if (disp.path("attached_pic").asInt(0) == 1 || video != null) {
                        continue;
                    }
                    String pix = text(s.path("pix_fmt"));
                    video = new Video(codec, profile(s), bitDepth(s, pix), integer(s.path("width")), integer(s.path("height")), pix);
                }
                case "audio" -> audio.add(new Audio(codec, profile(s), integer(s.path("channels")), lang,
                        disp.path("default").asInt(0) == 1));
                case "subtitle" -> subs.add(new Subtitle(codec, lang, disp.path("default").asInt(0) == 1,
                        disp.path("forced").asInt(0) == 1));
                default -> {
                }
            }
        }
        if (duration == null && video != null) {
            // Certains conteneurs ne donnent la durée que par piste.
            for (JsonNode s : root.path("streams")) {
                Double d = number(s.path("duration"));
                if (d != null && (duration == null || d > duration)) {
                    duration = d;
                }
            }
        }
        return new ProbeFacts(duration, text(format.path("format_name")), video, List.copyOf(audio), List.copyOf(subs));
    }

    private static String profile(JsonNode s) {
        String p = text(s.path("profile"));
        return p == null || "unknown".equalsIgnoreCase(p) ? null : p;
    }

    /** Profondeur : bits_per_raw_sample, sinon déduite du format de pixel (« yuv420p10le » → 10). */
    private static Integer bitDepth(JsonNode s, String pixFmt) {
        Integer bits = integer(s.path("bits_per_raw_sample"));
        if (bits != null && bits > 0) {
            return bits;
        }
        if (pixFmt == null) {
            return null;
        }
        if (pixFmt.matches(".*p1[02](le|be)?$") || pixFmt.contains("p10") || pixFmt.contains("p12")) {
            return pixFmt.contains("12") ? 12 : 10;
        }
        return 8;
    }

    /** Langue ISO 639-2 telle que dans le fichier (« jpn », « fre »…), « und » et vide → null. */
    private static String language(String l) {
        if (l == null) {
            return null;
        }
        String v = l.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() || v.equals("und") ? null : v;
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
