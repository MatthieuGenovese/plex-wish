package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.FakeAniList.Forced;
import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client AniList contre le faux serveur : lecture des fiches, limite de débit, pannes. Sans Quarkus, sans réseau. */
class AniListProviderTest {

    FakeAniList server;
    List<Duration> sleeps;
    MutableClock clock;
    AniListProvider provider;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = new FakeAniList();
        sleeps = new ArrayList<>();
        clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(Duration.ofMillis(2500), clock, d -> {
            sleeps.add(d);
            clock.now = clock.now.plus(d); // l'attente « passe » sans ralentir le test
        });
        provider = new AniListProvider(URI.create(server.url()), new ObjectMapper(), limiter, clock);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsTitlesYearPosterAndCleansTheSynopsis() throws Exception {
        server.onSearch("Frieren", FakeAniList.media(154587, "Sousou no Frieren", "Frieren: Beyond Journey's End", 2023, "TV", 28, "Frieren"));
        List<Candidate> found = provider.search("Frieren");
        assertEquals(1, found.size());
        Candidate c = found.get(0);
        assertEquals("154587", c.providerId());
        assertEquals("Sousou no Frieren", c.romaji());
        assertEquals("Frieren: Beyond Journey's End", c.english());
        assertEquals(2023, c.year());
        assertEquals(28, c.episodes());
        assertEquals("TV", c.format());
        assertEquals("Synopsis of Sousou no Frieren.\n\n(Source: test)", c.synopsis());
        assertTrue(c.posterUrl().startsWith("https://s4.anilist.co/"));
        assertTrue(c.posterLargeUrl().contains("/large/"));
        assertEquals(List.of("Sousou no Frieren", "Frieren: Beyond Journey's End", "Frieren"), c.titles());
        assertEquals("en", provider.synopsisLanguage());

        assertTrue(provider.byId("154587").isPresent());
        assertTrue(provider.byId("999").isEmpty(), "id inconnu : 404 → aucune fiche");
        assertTrue(provider.byId("abc").isEmpty());
        assertTrue(provider.search("rien").isEmpty());
    }

    @Test
    void entriesWithoutYearOrEpisodeCountAreRead() throws Exception {
        // Annonce sans date ni nombre d'épisodes (cas réel : NullPointerException avant correction).
        var upcoming = FakeAniList.media(1, "A Channel", null, null, "TV", null);
        upcoming.putNull("format");
        upcoming.putObject("startDate").putNull("year");
        server.onSearch("A Channel", upcoming);
        Candidate c = provider.search("A Channel").get(0);
        assertEquals(null, c.year());
        assertEquals(null, c.episodes());
        assertEquals(null, c.format());
        assertEquals("A Channel", c.displayTitle());
    }

    @Test
    void callsAreSpacedByTheMinimumInterval() throws Exception {
        provider.search("a");
        provider.search("b");
        provider.search("c");
        assertEquals(List.of(Duration.ofMillis(2500), Duration.ofMillis(2500)), sleeps);
    }

    @Test
    void rateLimit429PausesForRetryAfter() throws Exception {
        server.force(new Forced(429, Map.of("Retry-After", "42", "X-RateLimit-Remaining", "0"), "{\"errors\":[{\"message\":\"Too Many Requests.\"}]}"));
        ProviderUnavailableException e = assertThrows(ProviderUnavailableException.class, () -> provider.search("a"));
        assertEquals(Duration.ofSeconds(42), e.retryAfter().orElseThrow());
        sleeps.clear();
        provider.search("a"); // l'appel suivant attend la fin de la pause
        assertEquals(1, sleeps.size());
        assertTrue(sleeps.get(0).compareTo(Duration.ofSeconds(41)) >= 0, sleeps.toString());
    }

    @Test
    void exhaustedQuotaPausesUntilReset() throws Exception {
        Instant reset = clock.now.plusSeconds(30);
        server.force(new Forced(200, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", String.valueOf(reset.getEpochSecond())),
                "{\"data\":{\"Page\":{\"media\":[]}}}"));
        provider.search("a");
        sleeps.clear();
        provider.search("b");
        assertEquals(List.of(Duration.ofSeconds(30)), sleeps);
    }

    @Test
    void outagesAreReportedAsUnavailableNeverAsEmptyResults() {
        for (int status : new int[]{403, 500, 502, 503}) {
            server.force(new Forced(status, Map.of(), "{}"));
            ProviderUnavailableException e = assertThrows(ProviderUnavailableException.class, () -> provider.search("a"));
            assertTrue(e.getMessage().contains(String.valueOf(status)));
            assertFalse(e.retryAfter().isPresent());
        }
        server.force(new Forced(200, Map.of(), "pas du json"));
        assertThrows(ProviderUnavailableException.class, () -> provider.search("a"));
        server.stop();
        assertThrows(ProviderUnavailableException.class, () -> provider.search("a"), "serveur injoignable");
    }
}
