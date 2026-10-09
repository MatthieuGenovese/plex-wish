package fr.plexwish.animeserver.setup;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Faux DuckDNS (tests seulement, aucun appel réseau réel) : réponses au format de https://www.duckdns.org/spec.jsp. */
@Path("/test-fake/duckdns/update")
public class FakeDuckDns {

    static final String GOOD_TOKEN = "1b2c3d4e-0000-4a4a-8b8b-0123456789ab";
    static final List<String> DOMAINS = new CopyOnWriteArrayList<>();

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String update(@QueryParam("domains") String domains, @QueryParam("token") String token, @QueryParam("ip") String ip,
                         @QueryParam("verbose") String verbose) {
        DOMAINS.add(domains);
        return GOOD_TOKEN.equals(token) && "mon-anime".equals(domains) && (ip == null || ip.isEmpty()) && "true".equals(verbose)
                ? "OK\n198.51.100.7\n\nUPDATED" : "KO";
    }
}
