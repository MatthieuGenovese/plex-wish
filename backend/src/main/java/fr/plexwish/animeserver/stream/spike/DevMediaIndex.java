package fr.plexwish.animeserver.stream.spike;

import fr.plexwish.animeserver.stream.VideoMediaTypes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Liste des vidéos de {@code dev-media}, numérotées 1..n dans l'ordre alphabétique des chemins relatifs.
 * <p>
 * Le client ne fournit jamais de chemin : seulement un numéro. Protection path traversal :
 * <ul>
 *     <li>seuls les fichiers trouvés en parcourant la racine sont indexés ;</li>
 *     <li>le chemin réel (liens symboliques résolus) doit rester sous la racine réelle ;</li>
 *     <li>fichiers et dossiers cachés (commençant par '.') ignorés.</li>
 * </ul>
 * Spike uniquement : la liste est recalculée à chaque appel (quelques fichiers).
 */
public final class DevMediaIndex {

    public record Entry(int id, String relativePath, Path path, long size) {
    }

    private final Path root;

    public DevMediaIndex(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public List<Entry> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(realRoot)) {
            walk.filter(p -> !p.equals(realRoot))
                    .filter(p -> !isHidden(realRoot.relativize(p)))
                    .filter(Files::isRegularFile)
                    .filter(p -> VideoMediaTypes.isVideo(p.getFileName().toString()))
                    .filter(p -> isInside(realRoot, p))
                    .forEach(files::add);
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
        files.sort(Comparator.comparing(p -> relative(realRoot, p)));
        List<Entry> entries = new ArrayList<>(files.size());
        for (Path p : files) {
            try {
                entries.add(new Entry(entries.size() + 1, relative(realRoot, p), p, Files.size(p)));
            } catch (IOException ignored) {
                // fichier disparu entre-temps
            }
        }
        return entries;
    }

    public Optional<Entry> find(int id) {
        return list().stream().filter(e -> e.id() == id).findFirst();
    }

    /** Vérifie, au moment de servir, que le fichier est toujours un fichier régulier sous la racine. */
    public boolean isServable(Path file) {
        try {
            return Files.isRegularFile(file) && isInside(root.toRealPath(), file);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isInside(Path realRoot, Path p) {
        try {
            return p.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isHidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path realRoot, Path p) {
        return realRoot.relativize(p).toString().replace('\\', '/');
    }
}
