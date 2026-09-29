package fr.plexwish.animeserver.stream;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Extensions vidéo reconnues et type MIME renvoyé au lecteur. */
public final class VideoMediaTypes {

    private static final Map<String, String> BY_EXTENSION = Map.of(
            "mp4", "video/mp4",
            "m4v", "video/mp4",
            "mkv", "video/x-matroska",
            "webm", "video/webm",
            "avi", "video/x-msvideo");

    private VideoMediaTypes() {
    }

    public static Optional<String> forFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    public static boolean isVideo(String fileName) {
        return forFileName(fileName).isPresent();
    }
}
