#!/bin/sh
# Restauration d'une sauvegarde faite par backup-db.sh (README, « Restaurer »).
#
#   scripts/restore-db.sh backups/anime-db-20261003-031500.dump
#
# Étapes : sauvegarde de sécurité de la base actuelle (préfixe avant-restauration, hors rotation),
# arrêt du backend et du web, base recréée vide, restauration en une seule transaction, redémarrage.
# Au redémarrage, Flyway applique les migrations manquantes si la sauvegarde vient d'une version plus
# ancienne. Aucun mot de passe n'est passé ni affiché (connexion locale dans le conteneur postgres).
#
# Variables : COMPOSE_DIR (comme backup-db.sh), RESTORE_YES=1 pour ne pas demander de confirmation.
set -eu

PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') restore-db: $*"; }
die() { log "ÉCHEC : $*" >&2; exit 1; }

[ $# -eq 1 ] || die "usage : $0 <fichier .dump>"
case "$1" in /*) dump="$1" ;; *) dump="$(pwd)/$1" ;; esac
[ -f "$dump" ] && [ -s "$dump" ] || die "fichier introuvable ou vide : $1"

cd "$COMPOSE_DIR" || die "dossier introuvable : $COMPOSE_DIR"
[ -f docker-compose.yml ] || die "pas de docker-compose.yml dans $COMPOSE_DIR (définir COMPOSE_DIR)"
if docker compose version >/dev/null 2>&1; then
    compose() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
    compose() { docker-compose "$@"; }
else
    die "ni 'docker compose' ni 'docker-compose' trouvé"
fi

compose exec -T postgres pg_restore --list < "$dump" > /dev/null || die "fichier illisible par pg_restore : $1"

if [ "${RESTORE_YES:-}" != "1" ]; then
    echo "La base actuelle va être REMPLACÉE par $(basename "$dump")."
    echo "Une sauvegarde de sécurité de la base actuelle est faite avant. Taper RESTAURER pour continuer :"
    read -r answer
    [ "$answer" = "RESTAURER" ] || die "annulé"
fi

log "sauvegarde de sécurité de la base actuelle"
BACKUP_PREFIX=avant-restauration BACKUP_KEEP=5 COMPOSE_DIR="$COMPOSE_DIR" "$SCRIPTS/backup-db.sh" \
    || die "sauvegarde de sécurité impossible : restauration annulée, rien n'a été modifié"

log "arrêt du backend et du web (plus aucune écriture)"
compose stop web backend

log "base recréée vide"
compose exec -T postgres sh -c '
  set -e
  psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres \
       -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"'"'$POSTGRES_DB'"'"' AND pid <> pg_backend_pid()" > /dev/null
  dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"
  createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" "$POSTGRES_DB"'

log "restauration de $(basename "$dump")"
if ! compose exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --single-transaction --exit-on-error' < "$dump"; then
    log "ÉCHEC de la restauration : la base est vide. Pour revenir à l'état d'avant :" >&2
    log "  $0 $(ls -1t "${BACKUP_DIR:-$COMPOSE_DIR/backups}"/avant-restauration-*.dump 2>/dev/null | head -1)" >&2
    exit 1
fi

log "redémarrage"
compose up -d backend web
log "OK. Vérifier : 'docker compose ps' (backend healthy) puis se connecter."
