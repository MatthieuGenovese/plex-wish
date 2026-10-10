#!/bin/sh
# Construit et publie les images du serveur et du web (D1.1). Version : fichier VERSION à la racine du dépôt.
#
#   GHCR_OWNER=mon-compte-images scripts/publish-images.sh            # construit, vérifie, publie
#   scripts/publish-images.sh --no-push                                # construit et vérifie seulement
#
# Avant la première publication : `docker login ghcr.io` avec un jeton qui a le droit write:packages
# (PAS le jeton en lecture donné au NAS). Voir docs/DEPLOIEMENT.md, « Pour moi : images ».
#
# Refus de publier si :
#   - le dépôt a des modifications non commitées (l'image doit correspondre à un commit) ;
#   - cette version existe déjà sur le registre (jamais deux images différentes sous le même numéro) ;
#   - check-image-secrets.sh trouve un secret dans l'une des images.
# Variables : GHCR_OWNER (compte ou organisation GitHub), REGISTRY (défaut ghcr.io/$GHCR_OWNER ; un registre local
# pour les essais), ALLOW_DIRTY=1 (essais seulement), PULL_BASE=0 (ne pas rafraîchir les images de base),
# BACKEND_BUILD_ARGS / WEB_BUILD_ARGS (options docker build en plus ; les essais en remplacent les étapes de compilation).
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
push=1
[ "${1:-}" = "--no-push" ] && push=0

version=$(tr -d ' \r\n' < VERSION)
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "VERSION invalide : '$version' (attendu 1.2.3)" >&2; exit 1; }
if [ -n "${REGISTRY:-}" ]; then
    registry=$REGISTRY
else
    [ -n "${GHCR_OWNER:-}" ] || { echo "GHCR_OWNER manquant (compte ou organisation GitHub qui héberge les images)" >&2; exit 1; }
    registry="ghcr.io/$(echo "$GHCR_OWNER" | tr 'A-Z' 'a-z')"
fi
commit=$(git rev-parse --short=12 HEAD)
if [ -n "$(git status --porcelain --untracked-files=no)" ] && [ "${ALLOW_DIRTY:-}" != "1" ]; then
    echo "Le dépôt a des modifications non commitées : commiter d'abord (l'image doit correspondre à un commit)." >&2
    exit 1
fi

backend="$registry/anime-server-backend"
web="$registry/anime-server-web"

insecure=
case "$registry" in localhost:*|127.0.0.1:*) insecure=--insecure ;; esac # registre local d'essai, en HTTP
if [ "$push" = 1 ]; then
    for img in "$backend" "$web"; do
        # « manifest unknown » = version absente, cas normal ; toute autre erreur (accès refusé…) arrête tout.
        if answer=$(docker manifest inspect $insecure "$img:$version" 2>&1); then
            echo "$img:$version existe déjà sur le registre : augmenter VERSION." >&2
            exit 1
        fi
        if ! echo "$answer" | grep -Eqi 'manifest unknown|not found|no such manifest'; then
            echo "registre injoignable ou accès refusé pour $img ($answer). « docker login ghcr.io » fait avec le jeton d'écriture ?" >&2
            exit 1
        fi
    done
fi

# Images de base rafraîchies (correctifs de sécurité) ; PULL_BASE=0 pour réutiliser celles en cache.
pull=--pull
[ "${PULL_BASE:-1}" = "0" ] && pull=
echo "== Construction $version (commit $commit)"
# shellcheck disable=SC2086
docker build $pull ${BACKEND_BUILD_ARGS:-} -f backend/Dockerfile --build-arg APP_VERSION="$version" --build-arg GIT_COMMIT="$commit" \
    -t "$backend:$version" -t "$backend:sha-$commit" .
# shellcheck disable=SC2086
docker build $pull ${WEB_BUILD_ARGS:-} --build-arg APP_VERSION="$version" --build-arg GIT_COMMIT="$commit" \
    -t "$web:$version" -t "$web:sha-$commit" web

echo "== Recherche de secrets dans les images"
known=$(mktemp)
chmod 600 "$known"
trap 'rm -f "$known"' EXIT INT TERM
# Valeurs des secrets locaux (jamais affichées) : tout ce qui ressemble à un secret dans .env et dans les
# fichiers de signature Android, à partir de 12 caractères.
for f in .env android/local.properties android/keystore.properties android/app/keystore.properties; do
    [ -f "$f" ] || continue
    grep -E '^[A-Za-z0-9_.]*(SECRET|PASSWORD|TOKEN|KEY|PASS|secret|password|token|key|Password)[A-Za-z0-9_.]*=' "$f" \
        | sed 's/^[^=]*=//; s/^"//; s/"$//; s/\r$//' | awk 'length($0) >= 12' >> "$known" || true
done
scripts/check-image-secrets.sh "$backend:$version" "$known"
scripts/check-image-secrets.sh "$web:$version" "$known"

if [ "$push" = 0 ]; then
    echo "== Images prêtes (non publiées) : $backend:$version $web:$version"
    exit 0
fi
echo "== Publication"
for img in "$backend" "$web"; do
    docker push "$img:$version"
    docker push "$img:sha-$commit"
done
echo "== Publié : $backend:$version et $web:$version"
