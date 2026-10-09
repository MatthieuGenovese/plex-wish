package fr.plexwish.animeserver.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Briques de l'installation (D1.3), sans Quarkus : seuils d'espace disque, secrets en fichiers, réseau local. */
class SetupUnitTest {

    @Test
    void diskThresholdsFollowTheFreeSpace() {
        assertEquals(new DiskAdvice.Thresholds(50, 20, 75), DiskAdvice.propose(500_000_000_000L));
        assertEquals(new DiskAdvice.Thresholds(100, 40, 100), DiskAdvice.propose(2_000_000_000_000L));
        assertEquals(new DiskAdvice.Thresholds(5, 2, 6), DiskAdvice.propose(40_000_000_000L));
        // Très peu de place : le cache ne fait jamais passer l'espace libre sous l'alerte.
        DiskAdvice.Thresholds tiny = DiskAdvice.propose(12_000_000_000L);
        assertEquals(5, tiny.warnGb());
        assertTrue(tiny.remuxCapGb() <= 12 - 5 - 5 || tiny.remuxCapGb() == 1, "plafond " + tiny.remuxCapGb());
        assertTrue(tiny.criticalGb() < tiny.warnGb());
        // Espace inconnu (0) : valeurs minimales, cohérentes.
        DiskAdvice.Thresholds none = DiskAdvice.propose(0);
        assertTrue(none.criticalGb() < none.warnGb() && none.remuxCapGb() >= 1);
    }

    @Test
    void diskThresholdsAreChecked() {
        assertNull(DiskAdvice.problem(new DiskAdvice.Thresholds(50, 20, 50)));
        assertNotNull(DiskAdvice.problem(new DiskAdvice.Thresholds(20, 20, 50)));
        assertNotNull(DiskAdvice.problem(new DiskAdvice.Thresholds(50, 0, 50)));
        assertNotNull(DiskAdvice.problem(new DiskAdvice.Thresholds(50, 20, 0)));
    }

    @Test
    void secretsAreFilesOnlyTheServerCanRead(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir.resolve("keys"));
        assertFalse(store.exists(SecretStore.TMDB));
        store.write(SecretStore.TMDB, "eyJhbGciOiJIUzI1NiJ9.fake-token-value");
        Path f = dir.resolve("keys").resolve(SecretStore.TMDB);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertEquals("eyJhbGciOiJIUzI1NiJ9.fake-token-value", store.read(SecretStore.TMDB).orElseThrow());
        store.write(SecretStore.TMDB, "a-second-value-123");
        assertEquals("a-second-value-123", store.read(SecretStore.TMDB).orElseThrow());
        try (var files = Files.list(dir.resolve("keys"))) {
            assertEquals(1, files.count(), "pas de fichier temporaire laissé");
        }
        store.delete(SecretStore.TMDB);
        assertFalse(store.exists(SecretStore.TMDB));
        assertThrows(IllegalArgumentException.class, () -> store.write("../evil", "abcdefgh12"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.write(SecretStore.DDNS, "avec espace dedans"));
        assertFalse(e.getMessage().contains("espace"), "la valeur n'apparaît jamais dans le message");
    }

    @Test
    void localNetworkOnlyLiteralPrivateAddresses() {
        for (String ip : new String[]{"192.168.1.20", "10.0.0.5", "172.16.3.4", "127.0.0.1", "::1", "fd12:3456::1", "fe80::1",
                "100.101.102.103", "169.254.1.1"}) {
            assertTrue(LocalNetwork.isLocal(ip), ip);
        }
        for (String ip : new String[]{"203.0.113.5", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "localhost",
                "nas.example.com", "", null, "unknown"}) {
            assertFalse(LocalNetwork.isLocal(ip), String.valueOf(ip));
        }
    }

    @Test
    void entryHeaderUnknownValueIsPublic() {
        assertEquals(Entry.LAN, Entry.of("lan", "public"));
        assertEquals(Entry.LOCAL, Entry.of(null, "local"));
        assertEquals(Entry.PUBLIC, Entry.of("n'importe quoi", "local"));
    }

    @Test
    void duckdnsNameFromThePublicAddress() {
        assertEquals("mon-anime", DdnsService.subdomainOf("https://mon-anime.duckdns.org"));
        assertEquals("b", DdnsService.subdomainOf("https://a.b.duckdns.org"));
        assertEquals("", DdnsService.subdomainOf("https://anime.example.com"));
        assertEquals("", DdnsService.subdomainOf("https://duckdns.org.example.com"));
    }
}
