#!/bin/sh
# Démarrage du serveur dans le conteneur.
#
# Démarré en root (déploiement sur le NAS) : abandonne root pour l'utilisateur choisi par le conteneur init (celui
# qui peut lire le dossier média), puis se relance. Les secrets ne sont lus qu'APRÈS, par cet utilisateur : ils
# lui appartiennent (droits 600) et le conteneur n'a pas besoin du droit de lire les fichiers des autres.
# Démarré déjà non-root (stack de développement) : les secrets viennent de l'environnement (.env) ou des fichiers.
set -eu

SECRETS_DIR="${ANIME_SECRETS_DIR:-/run/anime/secrets}"

if [ "$(id -u)" = "0" ]; then
    run_as=""
    [ -r "$SECRETS_DIR/runtime-user" ] && run_as=$(cat "$SECRETS_DIR/runtime-user")
    case "$run_as" in
        [0-9]*:[0-9]*) ;;
        *) echo "entrypoint: utilisateur d'exécution inconnu (conteneur init lancé ?), repli sur 1000:1000" >&2
           run_as="1000:1000" ;;
    esac
    uid=${run_as%%:*}
    gid=${run_as##*:}
    if [ "$uid" = "0" ]; then
        echo "entrypoint: refus de faire tourner le serveur en root" >&2
        exit 1
    fi
    echo "entrypoint: serveur lancé avec l'utilisateur $uid:$gid"
    exec setpriv --reuid="$uid" --regid="$gid" --clear-groups -- "$0" "$@"
fi

# Secrets (un fichier chacun, dans keys/), sauf s'ils sont déjà dans l'environnement. Jamais affichés.
KEYS="$SECRETS_DIR/keys"
if [ -z "${JWT_SECRET:-}" ] && [ -r "$KEYS/jwt_secret" ]; then
    JWT_SECRET=$(cat "$KEYS/jwt_secret"); export JWT_SECRET
fi
if [ -z "${STREAM_SIGNING_SECRET:-}" ] && [ -r "$KEYS/stream_signing_secret" ]; then
    STREAM_SIGNING_SECRET=$(cat "$KEYS/stream_signing_secret"); export STREAM_SIGNING_SECRET
fi
if [ -z "${POSTGRES_PASSWORD:-}" ] && [ -r "$KEYS/db_password" ]; then
    POSTGRES_PASSWORD=$(cat "$KEYS/db_password"); export POSTGRES_PASSWORD
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/quarkus-run.jar
