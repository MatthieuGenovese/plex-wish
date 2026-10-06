package fr.plexwish.animeserver.media;

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
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dossier du cache des copies remuxées (ARCHITECTURE §23.3). Hors de /media, en écriture. Noms = empreinte seulement
 * ({@code ab/ab12…(64 hex).mkv}) : ni titre ni nom de fichier. Écriture dans {@code …mkv.part}, puis déplacement
 * atomique une fois la copie vérifiée.
 */
@ApplicationScoped
public class RemuxCache {

    private static final Logger LOG = Logger.getLogger(RemuxCache.class);
    static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FINAL = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{64}\\.mkv");

    private final Path root;

    @Inject
    public RemuxCache(MediaConfig config) {
        this(Path.of(config.remuxCachePath()));
    }

    RemuxCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Crée le dossier s'il manque ; false s'il n'est pas utilisable en écriture (droits PUID/PGID, volume absent). */
    public boolean usable() {
        try {
            Files.createDirectories(root);
            return Files.isDirectory(root) && Files.isWritable(root);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Empreinte d'une source : identifiant, taille, date (une source modifiée donne une autre clé). */
    public static String key(long mediaFileId, long size, OffsetDateTime modified) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            String s = "remux-v1:" + mediaFileId + ":" + size + ":" + (modified == null ? "" : modified.toInstant().toEpochMilli());
            return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path checked(String key, String suffix) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("clé de cache invalide");
        }
        Path p = root.resolve(key.substring(0, 2)).resolve(key + suffix).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("clé de cache invalide");
        }
        return p;
    }

    public Path finalPath(String key) {
        return checked(key, ".mkv");
    }

    public Path tempPath(String key) {
        return checked(key, ".mkv.part");
    }

    /** Copie prête (fichier ordinaire, pas un lien), sinon vide. */
    public Optional<Path> existing(String key) {
        try {
            Path p = finalPath(key);
            return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Optional.of(p) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Prépare le dossier de la copie et efface un reste de fichier temporaire. */
    public Path prepareTemp(String key) throws IOException {
        Path tmp = tempPath(key);
        Files.createDirectories(tmp.getParent());
        Files.deleteIfExists(tmp);
        return tmp;
    }

    /** Fichier temporaire vérifié → nom final, de façon atomique. */
    public Path publish(String key) throws IOException {
        Path tmp = tempPath(key);
        Path dst = finalPath(key);
        try {
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("déplacement atomique impossible dans le cache", e);
        }
        return dst;
    }

    public void delete(String key) {
        for (String suffix : new String[]{".mkv", ".mkv.part"}) {
            try {
                Files.deleteIfExists(checked(key, suffix));
            } catch (IOException | IllegalArgumentException e) {
                LOG.warnf("Cache de remux : suppression impossible (%s)", e.getClass().getSimpleName());
            }
        }
    }

    public long size(String key) {
        try {
            return Files.size(finalPath(key));
        } catch (IOException | IllegalArgumentException e) {
            return 0;
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

    /**
     * Ménage du démarrage : fichiers temporaires restés d'un arrêt, et copies que la base ne connaît plus.
     * Rien d'autre n'est touché (un fichier qui n'a pas la forme attendue reste en place).
     */
    public int sweep(Set<String> keep) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String name = p.getFileName().toString();
                boolean part = name.endsWith(".mkv.part") && KEY.matcher(name.substring(0, name.length() - 9)).matches();
                boolean orphan = FINAL.matcher(rel).matches() && !keep.contains(name.substring(0, 64));
                if (part || orphan) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warnf("Cache de remux : ménage incomplet (%s)", e.getClass().getSimpleName());
        }
        return removed;
    }
}
