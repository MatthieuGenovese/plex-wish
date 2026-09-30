package fr.plexwish.animeserver.common;

import io.vertx.core.http.HttpServerRequest;

/**
 * IP du client telle que vue par Quarkus. Quand la requête vient d'un proxy de confiance
 * ({@code quarkus.http.proxy.trusted-proxies}), Quarkus l'a déjà remplacée par la valeur de
 * X-Forwarded-For ; sinon c'est l'IP de la connexion TCP et le header est ignoré.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServerRequest request) {
        var address = request.remoteAddress();
        return address == null ? "unknown" : address.hostAddress();
    }
}
