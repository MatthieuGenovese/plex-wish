package fr.plexwish.animeserver.common.db;

import fr.plexwish.animeserver.library.OverrideRepository;
import fr.plexwish.animeserver.library.scan.ScanRunRepository;
import fr.plexwish.animeserver.library.subs.ExternalSubtitleRepository;
import fr.plexwish.animeserver.media.MediaProbeRepository;
import fr.plexwish.animeserver.metadata.MetadataRepository;
import fr.plexwish.animeserver.progress.ProgressRepository;
import fr.plexwish.animeserver.stream.StreamRepository;
import fr.plexwish.animeserver.tmdb.TmdbRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-régression du passage aux repositories : requêtes à filtres facultatifs (paramètres nuls), écritures dans une
 * transaction, contrôles d'accès des URL signées. Le comportement de bout en bout reste couvert par les tests d'API.
 */
@QuarkusTest
class RepositoryQueriesTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    Db db;
    @Inject
    ScanRunRepository runs;
    @Inject
    ProgressRepository progress;
    @Inject
    StreamRepository streams;
    @Inject
    OverrideRepository overrides;
    @Inject
    MetadataRepository metadata;
    @Inject
    TmdbRepository tmdb;
    @Inject
    ExternalSubtitleRepository subtitles;
    @Inject
    MediaProbeRepository probes;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : List.of("Show/Show - S01E01.mkv", "Show/Show - S01E02.mkv", "Show/Show 1x01.mkv",
                "Other/Other - S01E01.mkv")) {
            touch(ROOT, p);
        }
        assertEquals("SUCCESS", scan().getString("status"));
    }

    private long fileId(String path) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE relative_path = '" + path + "'");
    }

    private long episodeOf(String path) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file m ON m.id = e.media_file_id WHERE m.relative_path = '" + path + "'");
    }

    @Test
    void scanIssuesWithOptionalFilters() throws Exception {
        long id = runs.latestId().orElseThrow();
        long all = runs.countIssues(id, null, null);
        assertTrue(all >= 1, "le doublon est signalé");
        assertEquals(all, runs.issues(id, null, null, 500, 0).size());
        assertEquals(1, runs.issues(id, null, null, 1, 0).size(), "pagination");
        long duplicates = runs.countIssues(id, "DUPLICATE", null);
        assertEquals(1, duplicates);
        assertEquals(1, runs.countIssues(id, "DUPLICATE", "Sho"));
        assertEquals(0, runs.countIssues(id, "DUPLICATE", "Other"));
        assertEquals(0, runs.countIssues(id, null, "nothing-like-this"));
        Map<String, Long> counts = runs.issueCounts(id);
        assertEquals(all, counts.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(counts, runs.issueCounts(List.of(id)).get(id));
        assertEquals("SUCCESS", runs.find(id).orElseThrow().status());
        assertTrue(runs.find(-1).isEmpty());
    }

    @Test
    void progressSavedAndListedWithOrWithoutAnime() throws Exception {
        long user = createUser(unique("repo"), "repository-password", "USER");
        long episode = episodeOf("Show/Show - S01E02.mkv");
        long show = count(ds, "SELECT id FROM anime WHERE title = 'Show'");
        long other = count(ds, "SELECT id FROM anime WHERE title = 'Other'");
        assertTrue(progress.visibleDuration(episode).isPresent());
        assertNull(progress.visibleDuration(episode).get().seconds(), "durée inconnue : null, pas 0");
        assertTrue(progress.visibleDuration(-1).isEmpty());

        Instant saved = progress.upsert(user, episode, 300, 1400, false);
        assertNotNull(saved);
        progress.upsert(user, episode, 400, 1400, false);
        List<ProgressRepository.ProgressRow> mine = progress.mine(user, null);
        assertEquals(1, mine.size());
        assertEquals(400, mine.get(0).positionSeconds());
        assertEquals(1, progress.mine(user, show).size());
        assertTrue(progress.mine(user, other).isEmpty());
        assertEquals(1, progress.activities(user, null).size());
        assertTrue(progress.activities(user, other).isEmpty());
        // Épisode commencé : il est lui-même le « courant » ; le suivant non terminé n'existe pas.
        var current = progress.currentOrNextUnfinished(show, episode, user, true).orElseThrow();
        assertEquals(episode, current.episode().id());
        assertEquals(400, current.positionSeconds());
        assertTrue(progress.currentOrNextUnfinished(show, episode, user, false).isEmpty());
        assertEquals(show, progress.animeIdOfEpisode(episode).orElseThrow());
        assertEquals(1, progress.first(show).orElseThrow().episodeNumber());
    }

    @Test
    void signedUrlAccessChecks() throws Exception {
        long user = createUser(unique("repo"), "repository-password", "USER");
        long file = fileId("Show/Show - S01E01.mkv");
        StreamRepository.FileAccess ok = streams.mediaFileAccess(user, file);
        assertTrue(ok.userEnabled());
        assertEquals("Show/Show - S01E01.mkv", ok.relativePath());
        assertFalse(streams.mediaFileAccess(-1, file).userEnabled(), "utilisateur inconnu : refusé");
        assertNull(streams.mediaFileAccess(user, -1).relativePath(), "fichier inconnu : rien à servir");
        assertNull(streams.subtitleAccess(user, -1, file).relativePath());
        assertTrue(streams.episodeFile(-1).isEmpty());
        var ep = streams.episodeFile(episodeOf("Show/Show - S01E01.mkv")).orElseThrow();
        assertEquals(file, ep.fileId());
        assertTrue(ep.available());
    }

    @Test
    void overrideWrittenInTransactionAndListed() throws Exception {
        long file = fileId("Other/Other - S01E01.mkv");
        String path = overrides.pathOf(file).orElseThrow();
        db.runInTransaction(c -> overrides.upsert(c, path, "IGNORE", null, null, null, "test"));
        List<OverrideRepository.OverrideRow> all = overrides.list(null);
        assertEquals(1, all.size());
        assertEquals("IGNORE", all.get(0).action());
        assertNull(all.get(0).seasonNumber());
        assertEquals(file, overrides.list(file).get(0).mediaFileId());
        assertTrue(overrides.list(-1L).isEmpty());
        assertEquals(1, overrides.delete(path));
        assertEquals(0, overrides.delete(path));
        assertTrue(overrides.pathOf(-1).isEmpty());
    }

    @Test
    void adminListsWithoutFilters() throws Exception {
        long anime = count(ds, "SELECT count(*) FROM anime");
        assertEquals(2, anime);
        assertEquals(anime, metadata.countEntries(null, null));
        assertEquals(anime, metadata.entries(null, null, 50, 0).size());
        assertEquals(anime, metadata.countEntries("PENDING", null));
        assertEquals(1, metadata.countEntries(null, "%oth%"));
        assertEquals(anime, tmdb.countEntries(null, null, false));
        assertEquals(anime, tmdb.countEntries(null, null, true));
        assertEquals(0, subtitles.adminCount(ExternalSubtitleRepository.AdminFilter.ALL, null));
        assertEquals(0, subtitles.adminSummary().total());
        long videos = count(ds, "SELECT count(*) FROM media_file WHERE available AND kind = 'EPISODE'");
        assertEquals(videos, probes.countFiles(null, null));
        assertEquals(videos, probes.countFiles("pending", null));
        assertEquals(0, probes.countFiles("failed", null));
        assertEquals(count(ds, "SELECT count(*) FROM media_file WHERE available AND kind = 'EPISODE' AND relative_path LIKE '%1x01%'"),
                probes.countFiles(null, "%1x01%"));
        assertEquals(videos, probes.files(null, null, 50, 0).size());
        assertEquals(videos, probes.probeCounts().pending());
    }
}
