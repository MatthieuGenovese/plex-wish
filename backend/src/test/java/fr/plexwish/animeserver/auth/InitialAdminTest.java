package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.user.Role;
import fr.plexwish.animeserver.user.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin initial (§5.5) : créé à partir de INITIAL_ADMIN_* seulement s'il n'existe aucun admin.
 * Chaque test tourne dans une transaction annulée à la fin : la base partagée n'est pas modifiée.
 */
@QuarkusTest
@TestProfile(InitialAdminTest.InitialAdmin.class)
class InitialAdminTest {

    public static class InitialAdmin implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auth.initial-admin.username", "first-admin",
                    "auth.initial-admin.password", "first-admin-password");
        }
    }

    @Inject
    SecurityStartup startup;
    @Inject
    PasswordService passwords;

    private static void emptyUserTables() {
        RefreshToken.deleteAll();
        User.deleteAll();
    }

    @Test
    @TestTransaction
    void createdOnAnEmptyDatabaseWithABcryptHash() {
        emptyUserTables();
        startup.createInitialAdmin();

        User admin = User.findByLogin("first-admin").orElseThrow();
        assertEquals(Role.ADMIN, admin.role);
        assertTrue(admin.enabled);
        assertTrue(admin.passwordHash.matches("\\$2[aby]\\$12\\$.{53}"), "hash bcrypt coût 12");
        assertTrue(passwords.matches("first-admin-password", admin.passwordHash));
    }

    @Test
    @TestTransaction
    void ignoredOnceAnAdminExists() {
        emptyUserTables();
        startup.createInitialAdmin();
        startup.createInitialAdmin(); // redémarrage : rien de plus
        assertEquals(1, User.countAdmins());

        User.delete("username", "first-admin");
        User other = new User();
        other.username = "someone-else";
        other.passwordHash = "x";
        other.role = Role.ADMIN;
        other.persist();
        startup.createInitialAdmin();
        assertTrue(User.findByLogin("first-admin").isEmpty());
    }
}
