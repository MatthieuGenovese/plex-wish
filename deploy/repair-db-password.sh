#!/bin/sh
# Réparation sûre quand les secrets ont disparu alors que la base existe encore (le conteneur init l'a signalé).
# Ne supprime aucune donnée : crée un nouveau mot de passe, l'applique à la base existante, redémarre la stack.
#
#   sh app/repair-db-password.sh            (dans le dossier du projet sur le NAS, en root)
#   sh app/repair-db-password.sh --yes      sans question
set -eu
PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
cd "$(dirname "$0")/.."
. ./app/lib.sh

if [ "${1:-}" != "--yes" ]; then
    echo "Un nouveau mot de passe va être créé et appliqué à la base existante (aucune donnée effacée)."
    printf "Taper REPARER pour continuer : "
    read -r answer
    [ "$answer" = "REPARER" ] || die "annulé"
fi

log "arrêt du serveur et du web"
compose stop backend web > /dev/null 2>&1 || true
log "nouveau mot de passe (conteneur init)"
compose run --rm --no-deps init repair-db-password
log "démarrage de la base seule"
compose up -d --no-deps postgres
wait_healthy postgres 120 || die "la base ne démarre pas (voir : docker compose logs postgres)"
log "application du nouveau mot de passe à la base (connexion locale dans le conteneur, mot de passe jamais affiché)"
compose exec -T postgres sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
\getenv dbuser POSTGRES_USER
\set newpw `cat /run/secrets/pg/db_password`
ALTER ROLE :"dbuser" WITH PASSWORD :'newpw';
SQL
log "redémarrage de toute la stack"
compose up -d
wait_healthy backend 240 || die "le serveur ne démarre pas (voir : docker compose logs backend)"
log "OK : la base est intacte et le serveur s'y connecte avec le nouveau mot de passe"
