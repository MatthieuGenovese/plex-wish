#!/bin/sh
# Vérification de l'accès Internet (D1.7) : CGNAT, nom de domaine, port ouvert, boucle locale (hairpin).
# À lancer depuis un ordinateur (ou le NAS) du RÉSEAU de l'ami, puis depuis l'extérieur (4G, chez Matthieu) :
#
#   scripts/check-access.sh --domain mon-anime.duckdns.org [--wan-ip 88.12.34.56] [--outside]
#
# --wan-ip : l'adresse IPv4 « WAN » ou « Internet » affichée par la box (interface de la box) ; sans elle, le test
#            CGNAT est incomplet. --outside : lancé hors du réseau de l'ami (les conseils changent).
# Aucune donnée n'est envoyée ailleurs qu'à api.ipify.org (adresse publique, sans compte) et au site lui-même.
set -u
DOMAIN=""; WAN=""; WHERE=inside
while [ $# -gt 0 ]; do
    case "$1" in
        --domain) DOMAIN=$2; shift 2 ;;
        --wan-ip) WAN=$2; shift 2 ;;
        --outside) WHERE=outside; shift ;;
        *) echo "option inconnue : $1" >&2; exit 2 ;;
    esac
done
[ -n "$DOMAIN" ] || { echo "usage : $0 --domain <nom> [--wan-ip <adresse de la box>] [--outside]" >&2; exit 2; }

private() { # adresses non routées sur Internet : réseau privé ou CGNAT (100.64.0.0/10)
    case "$1" in
        10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
        100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*) return 0 ;;
        *) return 1 ;;
    esac
}

problems=0
echo "== 1. Adresse publique vue d'Internet"
PUBLIC=$(curl -4 -s --max-time 10 https://api.ipify.org || true)
if [ -z "$PUBLIC" ]; then
    echo "  ✘ impossible de joindre api.ipify.org : pas d'accès Internet depuis cet appareil ?"; problems=$((problems + 1))
else
    echo "  adresse publique : $PUBLIC"
fi

if [ "$WHERE" = inside ]; then
    echo "== 2. CGNAT (box qui partage son adresse avec d'autres clients du fournisseur)"
    if [ -z "$WAN" ]; then
        echo "  ? relancer avec --wan-ip <adresse IPv4 Internet affichée par la box> pour conclure."
    elif private "$WAN"; then
        echo "  ✘ la box a une adresse $WAN : réservée (privée ou CGNAT). La box n'a PAS d'adresse publique à elle."
        echo "    → demander une IPv4 « full-stack » / « dédiée » au fournisseur (conditions selon le fournisseur, voir AIDE-DEPLOIEMENT-AMI.md),"
        echo "      ou passer au plan B (Tailscale Funnel)."
        problems=$((problems + 1))
    elif [ -n "$PUBLIC" ] && [ "$WAN" != "$PUBLIC" ]; then
        echo "  ✘ la box annonce $WAN mais Internet voit $PUBLIC : CGNAT (ou un second routeur devant la box)."
        echo "    → même conduite : IPv4 full-stack auprès du fournisseur, ou plan B."
        problems=$((problems + 1))
    else
        echo "  ✔ pas de CGNAT : la box a sa propre adresse publique ($WAN)."
    fi
fi

echo "== 3. Nom de domaine"
RESOLVED=$( (getent ahostsv4 "$DOMAIN" 2> /dev/null || nslookup "$DOMAIN" 2> /dev/null) | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' | grep -v '^127\.' | tail -n 1)
if [ -z "$RESOLVED" ]; then
    echo "  ✘ $DOMAIN ne correspond à aucune adresse → vérifier le nom, et le jeton DuckDNS (assistant ou Réglages)."; problems=$((problems + 1))
elif [ "$WHERE" = inside ] && [ -n "$PUBLIC" ] && [ "$RESOLVED" != "$PUBLIC" ]; then
    echo "  ✘ $DOMAIN pointe vers $RESOLVED au lieu de $PUBLIC → le serveur n'a pas encore mis DuckDNS à jour"
    echo "    (5 min après le jeton), ou le jeton est faux (Administration > Réglages)."; problems=$((problems + 1))
else
    echo "  ✔ $DOMAIN → $RESOLVED"
fi

echo "== 4. Site en HTTPS (port 443 de la box, redirigé vers le NAS)"
code=$(curl -s --max-time 15 -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/setup/status")
case "$code" in
    200) echo "  ✔ le site répond, certificat valide." ;;
    000)
        insecure=$(curl -sk --max-time 15 -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/setup/status")
        if [ "$insecure" = 200 ]; then
            echo "  ✘ le site répond mais le certificat n'est pas (encore) valide → attendre quelques minutes après le"
            echo "    premier démarrage ; sinon, Caddy n'arrive pas à obtenir le certificat : port 443 mal redirigé."
        elif [ "$WHERE" = inside ]; then
            echo "  ✘ pas de réponse depuis la maison. Si le test « --outside » (4G) marche : la box ne fait pas la boucle locale"
            echo "    (hairpin) → l'activer si elle le propose, sinon utiliser le site depuis la 4G ou ajouter le nom au fichier hosts."
            echo "    Si --outside échoue aussi : redirection du port 443 absente ou mauvaise (TCP, vers l'adresse du NAS, port 8443)."
        else
            echo "  ✘ pas de réponse depuis l'extérieur → redirection de port (443 TCP → NAS:8443), pare-feu du DSM (autoriser 8443),"
            echo "    ou CGNAT (point 2)."
        fi
        problems=$((problems + 1)) ;;
    *) echo "  ✘ réponse inattendue ($code)"; problems=$((problems + 1)) ;;
esac

echo
if [ "$problems" = 0 ]; then echo "Tout est en ordre."; else echo "$problems point(s) à régler (conduite à tenir ci-dessus)."; fi
[ "$problems" = 0 ]
