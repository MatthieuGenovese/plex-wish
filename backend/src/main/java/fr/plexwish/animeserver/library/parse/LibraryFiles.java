package fr.plexwish.animeserver.library.parse;

import java.util.Locale;
import java.util.Set;

/** Classement d'un fichier de la bibliothèque d'après son nom seul (§7.1). */
public final class LibraryFiles {

    public enum Type { VIDEO, SUBTITLE, IMAGE, AUDIO, OTHER, IGNORED }

    private static final Set<String> VIDEO = Set.of("mkv", "mp4", "avi", "ogm", "ts", "m4v", "webm");
    private static final Set<String> SUBTITLE = Set.of("ass", "ssa", "srt", "sup", "idx", "sub", "vtt");
    private static final Set<String> IMAGE = Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp");
    private static final Set<String> AUDIO = Set.of("mp3", "flac", "m4a", "ape", "wav", "ogg", "opus", "aac");
    /** Dossiers techniques Synology, ignorés sans bruit. */
    private static final Set<String> IGNORED_DIRS = Set.of("@eadir", "#recycle", "#snapshot");

    private LibraryFiles() {
    }

    public static boolean isIgnoredDirectory(String name) {
        return IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static Type typeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (fileName.startsWith("._") || dot <= 0) {
            // AppleDouble macOS, fichier sans nom (".mkv") ou sans extension
            return fileName.startsWith("._") || dot == 0 ? Type.IGNORED : Type.OTHER;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (VIDEO.contains(ext)) {
            return Type.VIDEO;
        }
        if (SUBTITLE.contains(ext)) {
            return Type.SUBTITLE;
        }
        if (IMAGE.contains(ext)) {
            return Type.IMAGE;
        }
        return AUDIO.contains(ext) ? Type.AUDIO : Type.OTHER;
    }

    /** Type d'un chemin relatif ("/" comme séparateur), dossiers ignorés compris. */
    public static Type typeOfPath(String relativePath) {
        String[] parts = relativePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (isIgnoredDirectory(parts[i])) {
                return Type.IGNORED;
            }
        }
        return typeOf(parts[parts.length - 1]);
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
