package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN;
import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN_PASSWORD;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anti brute force (§5.4) avec la config par défaut : 5 échecs par (IP, identifiant), 20 par IP.
 * L'IP du client est fixée par X-Forwarded-For (127.0.0.1 est un proxy de confiance en test).
 */
@QuarkusTest
class BruteForceTest {

    private static final String PASSWORD = "brute-force-password";

    @Test
    void pairIsBlockedAfterFiveFailuresEvenWithTheRightPassword() {
        String name = unique("bf");
        createUser(name, PASSWORD, "USER");
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            login(name, "wrong-password", ip).then().statusCode(401);
        }
        login(name, PASSWORD, ip).then().statusCode(429).body("error", equalTo("TOO_MANY_ATTEMPTS"))
                .body("message", org.hamcrest.Matchers.matchesPattern("Trop de tentatives de connexion\\. Réessayez dans \\d+ minutes?\\."));
        // Le même compte, depuis une autre IP, n'est pas bloqué.
        login(name, PASSWORD, newIp()).then().statusCode(200);
        // Et la même IP peut toujours se connecter à un autre compte.
        login(ADMIN, ADMIN_PASSWORD, ip).then().statusCode(200);
    }

    @Test
    void anOutsiderCannotLockTheAdminOut() {
        String attacker = newIp();
        for (int i = 0; i < 12; i++) {
            login(ADMIN, "guess-" + i, attacker).then().statusCode(i < 5 ? 401 : 429);
        }
        // L'admin légitime, depuis son IP, se connecte normalement.
        login(ADMIN, ADMIN_PASSWORD, newIp()).then().statusCode(200);
        // Attaque répartie sur plusieurs IP : chacune ne bloque qu'elle-même.
        for (int n = 0; n < 5; n++) {
            String ip = newIp();
            for (int i = 0; i < 6; i++) {
                login(ADMIN, "guess", ip);
            }
        }
        login(ADMIN, ADMIN_PASSWORD, newIp()).then().statusCode(200);
    }

    @Test
    void ipIsBlockedAfterTwentyFailuresAcrossUsernames() {
        String ip = newIp();
        for (int i = 0; i < 20; i++) {
            login("nobody-" + i + "-" + System.nanoTime(), "wrong", ip).then().statusCode(401);
        }
        // Même avec un compte et un mot de passe valides : l'IP est bloquée.
        login(ADMIN, ADMIN_PASSWORD, ip).then().statusCode(429);
        login(ADMIN, ADMIN_PASSWORD, newIp()).then().statusCode(200);
    }

    @Test
    void successResetsThePairCounter() {
        String name = unique("reset-bf");
        createUser(name, PASSWORD, "USER");
        String ip = newIp();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                login(name, "wrong", ip).then().statusCode(401);
            }
            login(name, PASSWORD, ip).then().statusCode(200);
        }
    }

    @Test
    void blockedAnswerDoesNotRevealWhetherTheAccountExists() {
        String ip1 = newIp();
        String ip2 = newIp();
        for (int i = 0; i < 5; i++) {
            login(ADMIN, "wrong", ip1);
            login("ghost-account", "wrong", ip2);
        }
        String existing = login(ADMIN, "wrong", ip1).then().statusCode(429).extract().asString();
        String ghost = login("ghost-account", "wrong", ip2).then().statusCode(429).extract().asString();
        assertEquals(existing, ghost);
    }
}
