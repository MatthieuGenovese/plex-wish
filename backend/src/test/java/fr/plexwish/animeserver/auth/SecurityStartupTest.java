package fr.plexwish.animeserver.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifications de configuration qui bloquent le démarrage en prod (sans démarrer Quarkus). */
class SecurityStartupTest {

    private static final Optional<String> JWT = Optional.of("j".repeat(32));
    private static final Optional<String> STREAM = Optional.of("s".repeat(32));
    private static final Optional<String> URL = Optional.of("https://anime.example.com");

    private static List<String> problems(Optional<String> jwt, Optional<String> stream, Optional<String> url) {
        return SecurityStartup.configurationProblems(jwt, stream, url, true);
    }

    @Test
    void validConfiguration() {
        assertEquals(List.of(), problems(JWT, STREAM, URL));
    }

    @Test
    void missingOrShortSecretsAreRejectedWithoutPrintingThem() {
        assertEquals(1, problems(Optional.empty(), STREAM, URL).size());
        List<String> shortOne = problems(Optional.of("tooShortSecretValue"), STREAM, URL);
        assertEquals(1, shortOne.size());
        assertTrue(shortOne.get(0).contains("JWT_SECRET trop court"));
        assertTrue(shortOne.stream().noneMatch(p -> p.contains("tooShortSecretValue")));
        assertEquals(1, problems(JWT, Optional.of("x"), URL).size());
    }

    @Test
    void cookieWithoutSecureIsRejectedOutsideDev() {
        List<String> p = SecurityStartup.configurationProblems(JWT, STREAM, URL, false);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("cookie-secure"));
    }

    @Test
    void secretsMustDiffer() {
        assertEquals(1, problems(JWT, JWT, URL).size());
    }

    @Test
    void publicUrlIsRequiredAndMustBeHttp() {
        assertEquals(1, problems(JWT, STREAM, Optional.empty()).size());
        assertEquals(1, problems(JWT, STREAM, Optional.of("anime.example.com")).size());
        assertEquals(1, problems(JWT, STREAM, Optional.of("ftp://anime.example.com")).size());
    }

    @Test
    void publicUrlMustBeTheExactOriginTheBrowserSends() {
        assertEquals(List.of(), problems(JWT, STREAM, Optional.of("http://localhost:8080")));
        for (String variant : List.of("https://anime.example.com/", "https://anime.example.com:443",
                "https://Anime.Example.com", "https://anime.example.com/app")) {
            List<String> p = problems(JWT, STREAM, Optional.of(variant));
            assertEquals(1, p.size(), variant);
            assertTrue(p.get(0).contains("'https://anime.example.com'"), p.get(0));
        }
    }

    @Test
    void originIsNormalised() {
        assertEquals(Optional.of("https://anime.example.com"), OriginCheck.originOf("https://Anime.Example.com/"));
        assertEquals(Optional.of("https://anime.example.com"), OriginCheck.originOf("https://anime.example.com:443/x"));
        assertEquals(Optional.of("http://localhost:4200"), OriginCheck.originOf("http://localhost:4200"));
        assertEquals(Optional.empty(), OriginCheck.originOf("not a url"));
    }
}
