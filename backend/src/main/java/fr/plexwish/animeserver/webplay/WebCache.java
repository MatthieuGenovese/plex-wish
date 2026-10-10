package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.MediaConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cache web (docs/WEB-PLAYER.md §4.3) : un dossier par préparation, {@code ab/<empreinte>/}, écrit d'abord dans
 * {@code ab/<empreinte>.part/} puis renommé d'un coup. Hors de /media, en écriture. Noms de fichiers fixés par le
 * serveur (jamais venus d'un client ni du fichier vidéo) : {@link #NAME}.
 */
@ApplicationScoped
public class WebCache {

    private static final Logger LOG = Logger.getLogger(WebCache.class);
    static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    /** Seuls noms servis : pistes HLS, sous-titres, polices. */
    public static final Pattern NAME = Pattern.compile("s_\\d{1,2}\\.(m3u8|m4s)|sub_\\d{1,2}\\.(ass|vtt)|font_\\d{1,3}\\.(ttf|otf)");
    private static final String PART = ".part";

    private final Path root;

    @Inject
    public WebCache(MediaConfig config) {
        this(Path.of(config.webCachePath()));
    }

    WebCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Crée le dossier s'il manque ; false s'il n'est pas utilisable en écriture (droits, volume absent). */
    public boolean usable() {
        try {
            Files.createDirectories(root);
            return Files.isDirectory(root) && Files.isWritable(root);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Empreinte d'une préparation : sorte, fichier, taille, date (une source modifiée donne une autre clé). */
    public static String key(String kind, long mediaFileId, long size, OffsetDateTime modified) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            String s = "web-v1:" + kind + ":" + mediaFileId + ":" + size + ":" + (modified == null ? "" : modified.toInstant().toEpochMilli());
            return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    private Path checked(String key, String suffix) {
        if (!validKey(key)) {
            throw new IllegalArgumentException("clé de cache invalide");
        }
        Path p = root.resolve(key.substring(0, 2)).resolve(key + suffix).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("clé de cache invalide");
        }
        return p;
    }

    public Path dir(String key) {
        return checked(key, "");
    }

    public Path partDir(String key) {
        return checked(key, PART);
    }

    /**
     * Fichier d'une préparation : dossier final d'abord, puis (si {@code allowPart}) le dossier en cours d'écriture
     * (lecture pendant la préparation). Nom hors de la liste, lien ou absent : vide.
     */
    public Optional<Path> file(String key, String name, boolean allowPart) {
        if (name == null || !NAME.matcher(name).matches() || !validKey(key)) {
            return Optional.empty();
        }
        for (Path d : allowPart ? List.of(dir(key), partDir(key)) : List.of(dir(key))) {
            Path p = d.resolve(name).normalize();
            if (p.startsWith(d) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** Dossier temporaire vide pour une nouvelle préparation (un reste d'essai précédent est effacé). */
    public Path prepare(String key) throws IOException {
        Path part = partDir(key);
        deleteTree(part);
        Files.createDirectories(part);
        return part;
    }

    /** Dossier temporaire vérifié → dossier final, d'un coup (l'ancien, s'il existe, est remplacé). */
    public void publish(String key) throws IOException {
        Path part = partDir(key);
        Path dst = dir(key);
        deleteTree(dst);
        try {
            Files.move(part, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("déplacement atomique impossible dans le cache web", e);
        }
    }

    public void delete(String key) {
        try {
            deleteTree(dir(key));
            deleteTree(partDir(key));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warnf("Cache web : suppression impossible (%s)", e.getClass().getSimpleName());
        }
    }

    /** Taille d'une préparation (dossier final, sinon temporaire), en octets. */
    public long size(String key) {
        try {
            Path d = Files.isDirectory(dir(key)) ? dir(key) : partDir(key);
            return treeSize(d);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    public boolean exists(String key) {
        try {
            return Files.isDirectory(dir(key), LinkOption.NOFOLLOW_LINKS);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Espace libre du volume (-1 si inconnu). */
    public long usableSpace() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Taille totale du volume (-1 si inconnue). */
    public long totalSpace() {
        try {
            usable();
            return Files.getFileStore(root).getTotalSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Ménage du démarrage : dossiers temporaires restés d'un arrêt et préparations que la base ne connaît plus. Rien
     * d'autre n'est touché (un nom qui n'a pas la forme attendue reste en place).
     */
    public int sweep(Set<String> keep) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> dirs = Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) dirs.filter(f -> Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS))::iterator) {
                if (p.getParent() == null || p.getParent().equals(root) || p.equals(root)) {
                    continue;
                }
                String name = p.getFileName().toString();
                boolean part = name.endsWith(PART) && KEY.matcher(name.substring(0, name.length() - PART.length())).matches();
                boolean orphan = KEY.matcher(name).matches() && !keep.contains(name);
                if (part || orphan) {
                    deleteTree(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warnf("Cache web : ménage incomplet (%s)", e.getClass().getSimpleName());
        }
        return removed;
    }

    static long treeSize(Path d) {
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    static void deleteTree(Path d) throws IOException {
        if (!Files.exists(d, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> s = Files.walk(d)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
