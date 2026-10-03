package fr.plexwish.animeserver.poster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dossier des affiches. Noms tirés de l'empreinte : {@code ab/ab12…(64 hex).jpg}. Écriture dans un fichier
 * temporaire puis déplacement atomique (jamais de fichier à moitié écrit sous son nom final). Aucun chemin ne vient
 * du client ; même un chemin lu en base est vérifié (format exact, et résolu à l'intérieur du dossier).
 */
@ApplicationScoped
public class PosterStore {

    private static final Logger LOG = Logger.getLogger(PosterStore.class);
    /** Seule forme de chemin acceptée. */
    static final Pattern RELATIVE = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{64}\\.(jpg|png|webp)");
    private static final String TMP_PREFIX = ".tmp-";

    private final Path root;

    @Inject
    public PosterStore(PosterConfig config) {
        this(Path.of(config.path()));
    }

    /** Autre dossier géré de la même façon (images de la distribution). */
    public static PosterStore at(Path root) {
        return new PosterStore(root);
    }

    PosterStore(Path root) {
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

    public static String relativePath(String sha256, String extension) {
        return sha256.substring(0, 2) + "/" + sha256 + "." + extension;
    }

    /** Fichier d'un chemin relatif lu en base, s'il a la forme attendue, reste dans le dossier et existe. */
    public Optional<Path> existing(String relative) {
        if (relative == null || !RELATIVE.matcher(relative).matches()) {
            return Optional.empty();
        }
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    /** Écrit l'image si elle n'existe pas déjà (même empreinte = même contenu : rien à faire). */
    public void write(String relative, byte[] bytes) {
        if (!RELATIVE.matcher(relative).matches()) {
            throw new IllegalArgumentException("chemin d'affiche invalide");
        }
        Path target = root.resolve(relative).normalize();
        if (Files.isRegularFile(target)) {
            return;
        }
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = target.getParent().resolve(TMP_PREFIX + UUID.randomUUID());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        } catch (FileAlreadyExistsException e) {
            // Écrit entre-temps (même contenu) : rien à faire.
        } catch (IOException e) {
            throw new UncheckedIOException("écriture de l'affiche impossible", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // nettoyé au prochain démarrage
                }
            }
        }
    }

    public void delete(String relative) {
        existing(relative).ifPresent(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                LOG.warnf("Affiches : suppression impossible (%s)", e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Ménage (au démarrage de la tâche, après une purge) : fichiers temporaires laissés par une interruption, et
     * fichiers que plus aucune ligne ne référence. Ne touche qu'aux fichiers au format des affiches.
     */
    public int sweep(Set<String> referenced, Duration tmpMinAge) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        Instant tmpLimit = Instant.now().minus(tmpMinAge);
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))::iterator) {
                String name = p.getFileName().toString();
                String relative = root.relativize(p).toString().replace('\\', '/');
                boolean tmp = name.startsWith(TMP_PREFIX)
                        && Files.getLastModifiedTime(p).compareTo(FileTime.from(tmpLimit)) < 0;
                boolean orphan = RELATIVE.matcher(relative).matches() && !referenced.contains(relative);
                if (tmp || orphan) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warnf("Affiches : ménage incomplet (%s)", e.getClass().getSimpleName());
        }
        return removed;
    }

    /** Espace libre sur le volume des affiches (-1 s'il est inconnu). */
    public long usableSpace() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException | SecurityException e) {
            return -1;
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
