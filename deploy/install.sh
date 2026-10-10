#!/bin/sh
# Installation d'Anime Server sur un NAS Synology (D1.7). À lancer en root (Planificateur de tâches du DSM, ou SSH).
# Chaque étape est affichée en clair ; --dry-run montre ce qui serait fait, sans rien modifier.
# Lecture ligne par ligne pour l'ami : docs/DEPLOIEMENT.md, « La commande, expliquée ».
#
#   sh install.sh --version 1.0.0 --registry ghcr.io/mon-compte-images \
#                 --media /volume1/animes --domain mon-anime.duckdns.org [--dry-run]
#
# Options : --data DOSSIER (défaut /volume1/docker/anime-server), --web-cache DOSSIER (cache du lecteur web, défaut
#           <data>/web-cache ; un volume qui a de la place), --lan-port 8080, --public-port 8443,
#           --funnel (plan B, box derrière un CGNAT : accès par Tailscale Funnel au lieu de Caddy).
# Relancer la commande ne casse rien : les réglages existants (nas.env) et les données sont gardés.
set -eu
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

VERSION=""; REGISTRY=""; MEDIA=""; DOMAIN=""; DATA=/volume1/docker/anime-server; WEB_CACHE=
LAN_PORT=8080; PUBLIC_PORT=8443; PROFILE=caddy; DRY=no
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION=$2; shift 2 ;;
        --registry) REGISTRY=$2; shift 2 ;;
        --media) MEDIA=$2; shift 2 ;;
        --domain) DOMAIN=$2; shift 2 ;;
        --data) DATA=$2; shift 2 ;;
        --web-cache) WEB_CACHE=$2; shift 2 ;;
        --lan-port) LAN_PORT=$2; shift 2 ;;
        --public-port) PUBLIC_PORT=$2; shift 2 ;;
        --funnel) PROFILE=funnel; shift ;;
        --dry-run) DRY=yes; shift ;;
        *) echo "option inconnue : $1" >&2; exit 2 ;;
    esac
done

say() { echo "==> $*"; }
fail() { echo "ÉCHEC : $*" >&2; exit 1; }
# run : exécute la commande, ou l'affiche seulement en simulation.
run() { if [ "$DRY" = yes ]; then echo "    [simulation] $*"; else "$@"; fi; }

[ "$DRY" = yes ] && say "SIMULATION : rien ne sera modifié"
say "Vérifications"
echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || fail "--version manquante ou invalide (ex. 1.0.0)"
[ -n "$REGISTRY" ] || fail "--registry manquant"
echo "$DOMAIN" | grep -Eq '^[a-z0-9.-]+\.[a-z]{2,}$' || fail "--domain manquant ou invalide (ex. mon-anime.duckdns.org)"
[ -d "$MEDIA" ] || fail "dossier des vidéos introuvable : $MEDIA"
WEB_CACHE=${WEB_CACHE:-$DATA/web-cache}
case "$WEB_CACHE" in /*) ;; *) fail "--web-cache : chemin absolu attendu (ex. /volume2/anime-cache)" ;; esac
case "$WEB_CACHE/" in "$MEDIA"/*) fail "--web-cache ne doit pas être dans le dossier des vidéos (lecture seule)" ;; esac
[ "$(id -u)" = 0 ] || fail "à lancer en root (Planificateur de tâches : utilisateur « root »)"
command -v docker > /dev/null || fail "docker introuvable : installer Container Manager depuis le Centre de paquets"
if docker compose version > /dev/null 2>&1; then COMPOSE="docker compose"
elif command -v docker-compose > /dev/null; then COMPOSE="docker-compose"
else fail "docker compose introuvable : mettre à jour Container Manager"; fi
echo "    vidéos : $MEDIA (lecture seule) · dossier du projet : $DATA · adresse : https://$DOMAIN"
echo "    cache du lecteur web : $WEB_CACHE"

IMAGE="$REGISTRY/anime-server-backend:$VERSION"
# Accès aux images vérifié dès maintenant, même en simulation (rien n'est téléchargé) : un jeton refusé se voit ici.
say "Accès aux images de la version $VERSION"
insecure=""
case "$REGISTRY" in localhost:*|127.0.0.1:*) insecure="--insecure" ;; esac # registre local d'essai, en HTTP
for img in "$IMAGE" "$REGISTRY/anime-server-web:$VERSION"; do
    # shellcheck disable=SC2086
    if ! answer=$(docker manifest inspect $insecure "$img" 2>&1); then
        case "$answer" in
            *unauthorized*|*denied*|*authentication*)
                fail "accès refusé à $img : jeton de lecture absent, invalide ou révoqué (refaire la ligne « docker login ghcr.io » avec le bon jeton)" ;;
            *"manifest unknown"*|*"no such manifest"*|*"not found"*)
                fail "version $VERSION introuvable sur $REGISTRY : publiée ? (publish-images)" ;;
            *) fail "registre injoignable pour $img : $answer" ;;
        esac
    fi
done
echo "    OK"
say "Téléchargement de la version $VERSION (quelques minutes la première fois)"
run docker pull -q "$IMAGE"
run docker pull -q "$REGISTRY/anime-server-web:$VERSION"

say "Dossiers du projet dans $DATA"
run mkdir -p "$DATA/app" "$DATA/backups" "$DATA/posters" "$DATA/remux-cache" "$WEB_CACHE"
run chmod 700 "$DATA/backups"

say "Fichiers de déploiement (copiés depuis l'image : compose.yml, scripts de maintenance)"
if [ "$DRY" = yes ]; then
    echo "    [simulation] copie de /app/deploy de l'image vers $DATA/app, puis $DATA/compose.yml"
else
    docker run --rm --entrypoint tar "$IMAGE" -C /app/deploy -cf - . | tar -C "$DATA/app" -xf -
    cp "$DATA/app/compose.yml" "$DATA/compose.yml"
fi

# free_subnet : première plage /24 libre pour le réseau interne du projet (aucun réseau Docker ni route de la machine
# ne la chevauche). Les autres projets Docker du NAS (ou d'un PC d'essai) en occupent souvent une.
overlaps() { # overlaps <a.b.c.d/n> <e.f.g.h/m> : vrai si les deux plages se chevauchent
    echo "$1 $2" | awk '
        function ip(s, p) { split(s, p, "."); return ((p[1] * 256 + p[2]) * 256 + p[3]) * 256 + p[4] }
        { split($1, a, "/"); split($2, b, "/"); na = 2 ^ (32 - a[2]); nb = 2 ^ (32 - b[2])
          sa = ip(a[1]); sa -= sa % na; sb = ip(b[1]); sb -= sb % nb
          exit !(sa < sb + nb && sb < sa + na) }'
}
free_subnet() {
    used=$( { docker network ls -q | xargs -r docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2> /dev/null
              ip -4 route 2> /dev/null | awk '$1 ~ /\// { print $1 }'; } | tr ' ' '\n' | grep -E '^[0-9.]+/[0-9]+$' || true)
    for c in 172.30.64.0/24 172.30.66.0/24 172.30.70.0/24 172.30.80.0/24 172.30.90.0/24 10.213.17.0/24 10.213.18.0/24 10.213.19.0/24 192.168.213.0/24; do
        clash=no
        for u in $used; do overlaps "$c" "$u" && { clash=yes; break; }; done
        [ "$clash" = no ] && { echo "$c"; return 0; }
    done
    return 1
}

say "Réglages (nas.env, aucun secret dedans)"
if [ -f "$DATA/nas.env" ]; then
    echo "    nas.env existe déjà : gardé tel quel"
else
    if [ -z "${DOCKER_SUBNET:-}" ]; then
        DOCKER_SUBNET=$(free_subnet) || fail "aucune plage réseau libre pour Docker : indiquer DOCKER_SUBNET=a.b.c.0/24 avant la commande"
    fi
    echo "    réseau interne du projet : $DOCKER_SUBNET (plage libre sur cette machine)"
fi
if [ -f "$DATA/nas.env" ]; then
    :
elif [ "$DRY" = yes ]; then
    echo "    [simulation] écriture de $DATA/nas.env (version, registre, dossier des vidéos, adresse, ports)"
else
    cat > "$DATA/nas.env" <<ENV
# Réglages d'Anime Server sur ce NAS (écrit par install.sh le $(date '+%Y-%m-%d')). Aucun secret ici.
# Détails : docs/DEPLOIEMENT.md. Après une modification : sh app/restart.sh
ANIME_VERSION=$VERSION
IMAGE_PREFIX=$REGISTRY
MEDIA_PATH=$MEDIA
PUBLIC_DOMAIN=$DOMAIN
COMPOSE_PROFILES=$PROFILE
PUBLIC_PORT=$PUBLIC_PORT
LAN_PORT=$LAN_PORT
TZ=Europe/Paris
DOCKER_SUBNET=$DOCKER_SUBNET
# Cache du lecteur web (régénérable) : un volume qui a de la place. Le déplacer : docs/DEPLOIEMENT.md.
WEB_CACHE_PATH=$WEB_CACHE
ENV
    chmod 644 "$DATA/nas.env"
fi
# Installation antérieure à la phase 10 : nas.env sans cache web → ligne ajoutée (rien d'autre n'est touché).
if [ -f "$DATA/nas.env" ] && ! grep -q '^WEB_CACHE_PATH=' "$DATA/nas.env"; then
    if [ "$DRY" = yes ]; then echo "    [simulation] ajout de WEB_CACHE_PATH=$WEB_CACHE dans nas.env"
    else printf '%s\n' "WEB_CACHE_PATH=$WEB_CACHE" >> "$DATA/nas.env"; echo "    nas.env : cache du lecteur web ajouté ($WEB_CACHE)"; fi
fi

if [ "$PROFILE" = funnel ]; then
    say "Plan B Funnel : clé d'authentification Tailscale (une seule fois, jamais affichée)"
    if [ "$DRY" = yes ]; then
        echo "    [simulation] clé lue sur l'entrée standard, rangée dans le volume des secrets Tailscale (600)"
    else
        [ -n "${TS_AUTHKEY:-}" ] || { printf "    Coller la clé Tailscale (tskey-auth-…) puis Entrée : "; stty -echo 2> /dev/null || true; read -r TS_AUTHKEY; stty echo 2> /dev/null || true; echo; }
        printf '%s' "$TS_AUTHKEY" | docker run --rm -i -v anime-server_secrets_ts:/s --entrypoint sh "$IMAGE" -c 'umask 077; cat > /s/authkey'
    fi
fi

say "Démarrage (premier démarrage : création des secrets, de la base, puis du serveur)"
run sh -c "cd '$DATA' && $COMPOSE --env-file nas.env -f compose.yml up -d"

if [ "$DRY" = no ]; then
    say "Attente du serveur (jusqu'à 5 minutes)"
    i=0
    until [ "$(docker inspect -f '{{.State.Health.Status}}' anime-server-backend-1 2> /dev/null)" = healthy ]; do
        i=$((i + 5)); [ $i -le 300 ] || fail "le serveur ne démarre pas : envoyer à Matthieu le résultat de « cd $DATA && $COMPOSE --env-file nas.env -f compose.yml logs »"
        sleep 5
    done
fi

ip=$(ip -4 route get 1.1.1.1 2> /dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')
# Essai sur un PC Windows (WSL) : l'adresse de WSL n'est pas joignable depuis le navigateur, localhost l'est.
grep -qi microsoft /proc/version 2> /dev/null && ip=localhost
say "Terminé. Ouvrir dans un navigateur, depuis la maison : http://${ip:-ADRESSE-DU-NAS}:$LAN_PORT"
echo "    (l'adresse du NAS, celle qui sert à ouvrir le DSM, avec :$LAN_PORT à la fin), puis suivre l'assistant."
