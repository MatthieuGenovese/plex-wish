package fr.plexwish.animeserver.setup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Adresses du réseau local : privées (10/8, 172.16/12, 192.168/16, fc00::/7), boucle locale, lien local, et
 * 100.64.0.0/10 (adresses Tailscale, pour l'accès à distance de l'administrateur). Jamais de résolution DNS : seules
 * des adresses littérales sont acceptées.
 */
public final class LocalNetwork {

    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.%]+");

    private LocalNetwork() {
    }

    public static boolean isLocal(String ip) {
        if (ip == null || !LITERAL.matcher(ip).matches()) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
            }
            return (b[0] & 0xfe) == 0xfc; // fc00::/7
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
