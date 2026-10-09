package fr.plexwish.animeserver.auth;

/** Accès de test à la règle « même origine » de {@link OriginCheck} (package-private). */
public final class OriginCheckAccess {
    private OriginCheckAccess() {
    }

    public static boolean sameOrigin(String origin, String host) {
        return OriginCheck.sameOrigin(origin, host);
    }
}
