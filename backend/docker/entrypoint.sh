#!/bin/sh
# Démarrage du serveur dans le conteneur.
#
# 1. Secrets : s'ils ne sont pas déjà dans l'environnement (stack de développement, .env), ils sont lus dans
#    les fichiers créés par le conteneur init (un fichier par secret, droits 600). Jamais affichés.
# 2. Utilisateur : démarré en root (déploiement sur le NAS), le script abandonne root pour l'utilisateur choisi
#    par init (celui qui peut lire le dossier média), avant de lancer Java. Démarré déjà non-root : inchangé.
set -eu

SECRETS_DIR="${ANIME_SECRETS_DIR:-/run/anime/secrets}"

if [ -z "${JWT_SECRET:-}" ] && [ -r "$SECRETS_DIR/jwt_secret" ]; then
    JWT_SECRET=$(cat "$SECRETS_DIR/jwt_secret"); export JWT_SECRET
fi
if [ -z "${STREAM_SIGNING_SECRET:-}" ] && [ -r "$SECRETS_DIR/stream_signing_secret" ]; then
    STREAM_SIGNING_SECRET=$(cat "$SECRETS_DIR/stream_signing_secret"); export STREAM_SIGNING_SECRET
fi
if [ -z "${POSTGRES_PASSWORD:-}" ] && [ -r "$SECRETS_DIR/db_password" ]; then
    POSTGRES_PASSWORD=$(cat "$SECRETS_DIR/db_password"); export POSTGRES_PASSWORD
fi

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
    # shellcheck disable=SC2086
    exec setpriv --reuid="$uid" --regid="$gid" --clear-groups -- java $JAVA_OPTS -jar /app/quarkus-run.jar
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/quarkus-run.jar
