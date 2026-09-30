package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;

/** 401 sans authentification, 403 pour un USER sur les routes ADMIN, pour chaque endpoint protégé. */
@QuarkusTest
class PermissionsTest {

    record Endpoint(String method, String path, boolean adminOnly) {
        io.restassured.response.Response call(RequestSpecification spec) {
            RequestSpecification s = spec.contentType(ContentType.JSON);
            return switch (method) {
                case "GET" -> s.get(path);
                case "POST" -> s.body(Map.of("username", "x-" + System.nanoTime(), "password", "0123456789ab", "role", "USER")).post(path);
                case "PATCH" -> s.body(Map.of("enabled", true)).patch(path);
                default -> throw new IllegalArgumentException(method);
            };
        }
    }

    private static final List<Endpoint> PROTECTED = List.of(
            new Endpoint("GET", "/api/me", false),
            new Endpoint("GET", "/api/admin/users", true),
            new Endpoint("POST", "/api/admin/users", true),
            new Endpoint("PATCH", "/api/admin/users/1", true),
            new Endpoint("GET", "/api/admin/debug/client-ip", true));

    @Inject
    JwtKeys keys;

    private String userToken;

    private String userToken() {
        if (userToken == null) {
            String name = unique("plain");
            createUser(name, "plain-user-password", "USER");
            userToken = accessToken(name, "plain-user-password");
        }
        return userToken;
    }

    @Test
    void anonymousGets401Everywhere() {
        for (Endpoint e : PROTECTED) {
            e.call(given()).then().statusCode(401);
        }
    }

    @Test
    void userGets403OnAdminRoutesAnd200Elsewhere() {
        for (Endpoint e : PROTECTED) {
            e.call(given().auth().oauth2(userToken())).then().statusCode(e.adminOnly() ? 403 : 200);
        }
    }

    @Test
    void adminIsAllowedEverywhere() {
        String admin = adminToken();
        given().auth().oauth2(admin).get("/api/me").then().statusCode(200);
        given().auth().oauth2(admin).get("/api/admin/users").then().statusCode(200);
        given().auth().oauth2(admin).get("/api/admin/debug/client-ip").then().statusCode(200);
    }

    @Test
    void publicRoutesStayPublic() {
        given().get("/api/status").then().statusCode(200);
        given().post("/api/auth/refresh").then().statusCode(401); // 401 métier (pas de cookie), pas un refus d'accès
        given().post("/api/auth/logout").then().statusCode(204);
    }

    @Test
    void expiredOrWrongIssuerTokensAreRejected() {
        String expired = Jwt.issuer(AccessTokenService.ISSUER).subject("1").upn("admin").groups("ADMIN")
                .issuedAt(System.currentTimeMillis() / 1000 - 7200).expiresAt(System.currentTimeMillis() / 1000 - 3600)
                .jws().algorithm(SignatureAlgorithm.HS256).sign(keys.key());
        given().auth().oauth2(expired).get("/api/admin/users").then().statusCode(401);

        String otherIssuer = Jwt.issuer("someone-else").subject("1").upn("admin").groups("ADMIN")
                .jws().algorithm(SignatureAlgorithm.HS256).sign(keys.key());
        given().auth().oauth2(otherIssuer).get("/api/admin/users").then().statusCode(401);
    }

    @Test
    void roleComesFromTheSignedTokenNotFromTheClient() {
        // Un USER ne peut pas se donner le rôle ADMIN : il faudrait la clé pour signer.
        String token = userToken();
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".AAAA";
        given().auth().oauth2(tampered).get("/api/admin/users").then().statusCode(401);
    }
}
