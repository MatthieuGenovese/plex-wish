#!/bin/sh
# Restauration d'une sauvegarde de la base (D1.5). Dans le dossier du projet sur le NAS, en root :
#
#   sh app/restore.sh backups/anime-db-20261009-030000.dump
#   sh app/restore.sh <fichier> --yes        sans question (utilisé par update.sh pour le retour arrière)
#
# Étapes : vérification du fichier, sauvegarde de sécurité de la base actuelle (« avant-restauration »), arrêt du
# serveur et du web, base recréée puis restaurée en une seule transaction, redémarrage, contrôle de santé.
# Une sauvegarde plus ancienne que le serveur est complétée au démarrage par les migrations (Flyway).
# Aucun mot de passe n'est passé ni affiché : tout se fait par la connexion locale du conteneur postgres.
set -eu
cd "$(dirname "$0")/.."
. ./app/lib.sh

[ $# -ge 1 ] || die "usage : sh app/restore.sh <fichier .dump> [--yes]"
case "$1" in /*) dump="$1" ;; *) dump="$(pwd)/$1" ;; esac
[ -s "$dump" ] || die "fichier introuvable ou vide : $1"

compose up -d postgres > /dev/null 2>&1
wait_healthy postgres 120 || die "la base ne démarre pas"
compose exec -T postgres pg_restore --list < "$dump" > /dev/null || die "fichier illisible (pas une sauvegarde de la base) : $1"

if [ "${2:-}" != "--yes" ]; then
    echo "La base actuelle va être REMPLACÉE par $(basename "$dump")."
    echo "Une sauvegarde de sécurité de la base actuelle est faite avant. Taper RESTAURER pour continuer :"
    read -r answer
    [ "$answer" = "RESTAURER" ] || die "annulé"
fi

log "sauvegarde de sécurité de la base actuelle"
compose up -d backup > /dev/null 2>&1
compose exec -T backup sh /app-scripts/backup-loop.sh once avant-restauration \
    || die "sauvegarde de sécurité impossible : restauration annulée, rien n'a été modifié"

log "arrêt du serveur et du web"
compose stop backend web > /dev/null 2>&1 || true

log "restauration de $(basename "$dump")"
compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" "$POSTGRES_DB"' \
    || die "impossible de recréer la base (la sauvegarde de sécurité est dans backups/)"
compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --single-transaction --exit-on-error' < "$dump" \
    || die "restauration en échec : base vide ; relancer avec la sauvegarde avant-restauration-*.dump"

log "redémarrage"
compose up -d > /dev/null 2>&1
wait_healthy backend 300 || die "le serveur ne redémarre pas (voir : docker compose logs backend)"
log "OK : base restaurée depuis $(basename "$dump"), serveur en bonne santé"
