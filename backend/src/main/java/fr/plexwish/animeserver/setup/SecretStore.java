package fr.plexwish.animeserver.setup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Secrets saisis dans l'interface d'administration (clé TMDB, jeton du DNS dynamique) : un fichier par secret, droits
 * 600, dans le volume des secrets créé par le conteneur init. Jamais en base, jamais journalisés, jamais renvoyés au
 * navigateur (seulement « configuré » ou non).
 */
@ApplicationScoped
public class SecretStore {

    public static final String TMDB = "tmdb_token";
    public static final String DDNS = "ddns_token";
    private static final Set<String> NAMES = Set.of(TMDB, DDNS);
    private static final Pattern PRINTABLE = Pattern.compile("[\\x21-\\x7e]{8,1024}");
    private static final Logger LOG = Logger.getLogger(SecretStore.class);
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path dir;

    @Inject
    public SecretStore(SetupConfig config) {
        this(Path.of(config.secretsDir()));
    }

    SecretStore(Path dir) {
        this.dir = dir;
    }

    public Optional<String> read(String name) {
        Path f = file(name);
        try {
            if (!Files.isReadable(f)) {
                return Optional.empty();
            }
            String v = Files.readString(f, StandardCharsets.UTF_8).trim();
            return v.isEmpty() ? Optional.empty() : Optional.of(v);
        } catch (IOException e) {
            LOG.warnf("Secret %s illisible : %s", name, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public boolean exists(String name) {
        return read(name).isPresent();
    }

    /** Écriture atomique, droits 600 avant d'y mettre la valeur. */
    public void write(String name, String value) throws IOException {
        Path target = file(name); // nom inconnu : refusé avant toute écriture
        if (value == null || !PRINTABLE.matcher(value).matches()) {
            throw new IllegalArgumentException("valeur invalide"); // jamais la valeur dans le message
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve("." + name + ".tmp");
        Files.deleteIfExists(tmp);
        if (POSIX) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, value, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.infof("Secret %s enregistré", name);
    }

    public void delete(String name) throws IOException {
        if (Files.deleteIfExists(file(name))) {
            LOG.infof("Secret %s supprimé", name);
        }
    }

    Path file(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("secret inconnu : " + name);
        }
        return dir.resolve(name);
    }
}
