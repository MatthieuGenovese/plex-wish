package fr.plexwish.animeserver.webplay;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File d'attente des préparations web (web_job) : prise d'un travail, préventif réservé à la nuit, remise en file. */
@QuarkusTest
class QueueRepositoriesTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebJobRepository jobs;

    long file;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        touch(ROOT, "Show/Show - S01E01.mkv");
        assertEquals("SUCCESS", scan().getString("status"));
        file = count(ds, "SELECT id FROM media_file");
    }

    @Test
    void claimTakesUrgentWorkAndKeepsPreventiveForTheNight() throws Exception {
        jobs.insert(file, WebPrepService.BASE, "k-base", 3, null, WebPrepService.PRIORITY_NIGHT, null);
        jobs.insert(file, WebPrepService.BASE, "k-dup", 3, null, 0, null); // déjà là : ignoré (ON CONFLICT)
        assertEquals("k-base", jobs.job(file, WebPrepService.BASE).orElseThrow().key());
        assertFalse(jobs.hasRunnableQueued(false), "préventif : seulement la nuit");
        assertTrue(jobs.claimNext(WebPrepService.BASE, false).isEmpty());
        assertTrue(jobs.hasRunnableQueued(true));
        assertTrue(jobs.claimNext(WebPrepService.CONV, true).isEmpty(), "une seule sorte par file");

        assertEquals(file, jobs.claimNext(WebPrepService.BASE, true).orElseThrow());
        assertTrue(jobs.claimNext(WebPrepService.BASE, true).isEmpty(), "déjà pris");
        WebJobRepository.JobRow running = jobs.job(file, WebPrepService.BASE).orElseThrow();
        assertEquals("RUNNING", running.status());
        assertEquals("PROBE", running.phase());

        jobs.setPhase(file, WebPrepService.BASE, "HLS", null);
        assertEquals("HLS", jobs.job(file, WebPrepService.BASE).orElseThrow().phase());
        jobs.requeueRunning();
        assertEquals("QUEUED", jobs.job(file, WebPrepService.BASE).orElseThrow().status());

        jobs.markFailed(file, WebPrepService.BASE, "raison", false, 3);
        WebJobRepository.JobRow failed = jobs.job(file, WebPrepService.BASE).orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals(1, failed.attempts());
        assertTrue(failed.nextAttempt() != null, "nouvel essai programmé");
        assertTrue(jobs.retry(file, WebPrepService.BASE));
        assertEquals(1, jobs.job(file, WebPrepService.BASE).orElseThrow().priority(), "priorité admin au moins");
        assertEquals(0, jobs.readyBytes());
        assertEquals(List.of(), jobs.readyKeys());
        jobs.deleteByKey("k-base");
        assertTrue(jobs.job(file, WebPrepService.BASE).isEmpty());
    }
}
