package fr.plexwish.animeserver.stream.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DevMediaIndexTest {

    @TempDir
    Path tmp;

    private static void touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{1, 2, 3});
    }

    @Test
    void listsOnlyVisibleVideosSortedWithStableIds() throws IOException {
        Path root = tmp.resolve("media");
        touch(root.resolve("b.mp4"));
        touch(root.resolve("Show/Season 01/Show - S01E01.mkv"));
        touch(root.resolve("notes.txt"));
        touch(root.resolve(".hidden.mkv"));
        touch(root.resolve(".trash/old.mkv"));

        List<DevMediaIndex.Entry> entries = new DevMediaIndex(root).list();

        assertEquals(List.of("Show/Season 01/Show - S01E01.mkv", "b.mp4"),
                entries.stream().map(DevMediaIndex.Entry::relativePath).toList());
        assertEquals(1, entries.get(0).id());
        assertEquals(2, entries.get(1).id());
        assertEquals(3, entries.get(1).size());
    }

    @Test
    void missingRootGivesEmptyList() {
        assertTrue(new DevMediaIndex(tmp.resolve("does-not-exist")).list().isEmpty());
    }

    @Test
    void unknownIdIsNotFound() throws IOException {
        Path root = tmp.resolve("media");
        touch(root.resolve("a.mp4"));
        DevMediaIndex index = new DevMediaIndex(root);
        assertTrue(index.find(1).isPresent());
        assertFalse(index.find(0).isPresent());
        assertFalse(index.find(2).isPresent());
        assertFalse(index.find(-1).isPresent());
    }

    @Test
    void fileOutsideRootIsNeverServable() throws IOException {
        Path root = tmp.resolve("media");
        touch(root.resolve("a.mp4"));
        Path outside = tmp.resolve("secret.mp4");
        touch(outside);
        DevMediaIndex index = new DevMediaIndex(root);
        assertFalse(index.isServable(outside));
        assertFalse(index.isServable(root.resolve("../secret.mp4")));
        assertTrue(index.isServable(root.resolve("a.mp4")));
    }

    @Test
    void symlinkEscapingRootIsIgnored() throws IOException {
        Path root = tmp.resolve("media");
        touch(root.resolve("a.mp4"));
        Path outside = tmp.resolve("secret.mp4");
        touch(outside);
        try {
            Files.createSymbolicLink(root.resolve("link.mp4"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows sans mode développeur : pas le droit de créer un lien symbolique.
            assumeTrue(false, "liens symboliques non disponibles sur cette machine");
        }
        List<DevMediaIndex.Entry> entries = new DevMediaIndex(root).list();
        assertEquals(List.of("a.mp4"), entries.stream().map(DevMediaIndex.Entry::relativePath).toList());
    }
}
