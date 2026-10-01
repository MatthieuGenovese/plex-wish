package fr.plexwish.animeserver.library.scan;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import java.util.Map;

/**
 * Mêmes cas, avec des lots d'UN fichier : le fichier écarté a déjà été écrit en base dans un lot précédent,
 * l'épisode doit être rebranché par UPDATE (et non corrigé avant insertion).
 */
@QuarkusTest
@TestProfile(DuplicateReliabilityAcrossBatchesTest.OneFilePerBatch.class)
class DuplicateReliabilityAcrossBatchesTest extends DuplicateReliabilityTest {

    public static class OneFilePerBatch implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("anime.library.batch-size", "1");
        }
    }
}
