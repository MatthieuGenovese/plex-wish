#!/bin/sh
# Installation d'Anime Server sur un NAS Synology (D1.7). À lancer en root (Planificateur de tâches du DSM, ou SSH).
# Chaque étape est affichée en clair ; --dry-run montre ce qui serait fait, sans rien modifier.
# Lecture ligne par ligne pour l'ami : docs/DEPLOIEMENT.md, « La commande, expliquée ».
#
#   sh install.sh --version 1.0.0 --registry ghcr.io/mon-compte-images \
#                 --media /volume1/animes --domain mon-anime.duckdns.org [--dry-run]
#
# Options : --data DOSSIER (défaut /volume1/docker/anime-server), --lan-port 8080, --public-port 8443,
#           --funnel (plan B, box derrière un CGNAT : accès par Tailscale Funnel au lieu de Caddy).
# Relancer la commande ne casse rien : les réglages existants (nas.env) et les données sont gardés.
set -eu
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

VERSION=""; REGISTRY=""; MEDIA=""; DOMAIN=""; DATA=/volume1/docker/anime-server
LAN_PORT=8080; PUBLIC_PORT=8443; PROFILE=caddy; DRY=no
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION=$2; shift 2 ;;
        --registry) REGISTRY=$2; shift 2 ;;
        --media) MEDIA=$2; shift 2 ;;
        --domain) DOMAIN=$2; shift 2 ;;
        --data) DATA=$2; shift 2 ;;
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
[ "$(id -u)" = 0 ] || fail "à lancer en root (Planificateur de tâches : utilisateur « root »)"
command -v docker > /dev/null || fail "docker introuvable : installer Container Manager depuis le Centre de paquets"
if docker compose version > /dev/null 2>&1; then COMPOSE="docker compose"
elif command -v docker-compose > /dev/null; then COMPOSE="docker-compose"
else fail "docker compose introuvable : mettre à jour Container Manager"; fi
echo "    vidéos : $MEDIA (lecture seule) · dossier du projet : $DATA · adresse : https://$DOMAIN"

IMAGE="$REGISTRY/anime-server-backend:$VERSION"
say "Téléchargement de la version $VERSION (quelques minutes la première fois)"
run docker pull -q "$IMAGE"
run docker pull -q "$REGISTRY/anime-server-web:$VERSION"

say "Dossiers du projet dans $DATA"
run mkdir -p "$DATA/app" "$DATA/backups" "$DATA/posters" "$DATA/remux-cache"
run chmod 700 "$DATA/backups"

say "Fichiers de déploiement (copiés depuis l'image : compose.yml, scripts de maintenance)"
if [ "$DRY" = yes ]; then
    echo "    [simulation] copie de /app/deploy de l'image vers $DATA/app, puis $DATA/compose.yml"
else
    docker run --rm --entrypoint tar "$IMAGE" -C /app/deploy -cf - . | tar -C "$DATA/app" -xf -
    cp "$DATA/app/compose.yml" "$DATA/compose.yml"
fi

say "Réglages (nas.env, aucun secret dedans)"
if [ -f "$DATA/nas.env" ]; then
    echo "    nas.env existe déjà : gardé tel quel"
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
DOCKER_SUBNET=${DOCKER_SUBNET:-172.30.64.0/24}
ENV
    chmod 644 "$DATA/nas.env"
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
say "Terminé. Ouvrir dans un navigateur, depuis la maison : http://${ip:-ADRESSE-DU-NAS}:$LAN_PORT"
echo "    (l'adresse du NAS, celle qui sert à ouvrir le DSM, avec :$LAN_PORT à la fin), puis suivre l'assistant."
