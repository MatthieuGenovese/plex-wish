package fr.plexwish.animeserver.stream;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** URL signées du lecteur web (docs/WEB-PLAYER.md §9, S5) : liées au fichier, à la préparation et à la ressource. */
class WebSignatureTest {

    static final String KEY = "a".repeat(64);
    static final String OTHER = "b".repeat(64);
    final StreamSigner signer = new StreamSigner("secret-de-test-0123456789abcdef-xyz", Duration.ofHours(6),
            Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));

    private String sig(String query) {
        return query.substring(query.indexOf("sig=") + 4);
    }

    @Test
    void signatureIsBoundToEverything() {
        long exp = signer.expiry();
        String s = sig(signer.webQuery(5, KEY, "s_1.m3u8", 7, exp));
        assertEquals(StreamSigner.Check.VALID, signer.checkWeb(5, KEY, "s_1.m3u8", 7, exp, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "s_2.m3u8", 7, exp, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(6, KEY, "s_1.m3u8", 7, exp, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, OTHER, "s_1.m3u8", 7, exp, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "s_1.m3u8", 8, exp, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "s_1.m3u8", 7, exp + 1, s));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "s_1.m3u8", 7, exp, null));
        // Liste des pistes audio de la playlist maîtresse : signée aussi.
        String m = sig(signer.webQuery(5, KEY, "master.m3u8;a=1,2", 7, exp));
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "master.m3u8;a=1", 7, exp, m));
        // Une signature de l'original n'ouvre pas une ressource web, et inversement.
        String original = signer.signature(5, 7, exp);
        assertEquals(StreamSigner.Check.INVALID, signer.checkWeb(5, KEY, "s_1.m3u8", 7, exp, original));
        assertEquals(StreamSigner.Check.INVALID, signer.check(5, 7, exp, s));
    }

    @Test
    void expiredLinksAreRecognised() {
        long past = Instant.parse("2026-10-10T11:00:00Z").getEpochSecond();
        String s = sig(signer.webQuery(5, KEY, "s_0.m4s", 7, past));
        assertEquals(StreamSigner.Check.EXPIRED, signer.checkWeb(5, KEY, "s_0.m4s", 7, past, s));
    }
}
