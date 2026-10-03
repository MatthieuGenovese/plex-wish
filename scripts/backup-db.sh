#!/bin/sh
# Sauvegarde de la base PostgreSQL de la stack (README, « Sauvegarde de la base »).
#
# Format « custom » de pg_dump (compressé, restauration sélective possible), un fichier horodaté par
# exécution, rotation : seuls les BACKUP_KEEP derniers sont gardés.
# Le dump est fait DANS le conteneur postgres (connexion locale) : aucun mot de passe n'est passé,
# affiché ni écrit dans les logs.
#
#   scripts/backup-db.sh                         # depuis la racine du dépôt (docker-compose.yml)
#   BACKUP_DIR=/volume1/backups/anime BACKUP_KEEP=30 /chemin/du/depot/scripts/backup-db.sh
#
# Variables (toutes facultatives) :
#   BACKUP_DIR     dossier de sortie (défaut : <dépôt>/backups)
#   BACKUP_KEEP    nombre de sauvegardes gardées (défaut : 14)
#   BACKUP_PREFIX  début du nom de fichier (défaut : anime-db) ; la rotation ne touche qu'à ce préfixe
#   COMPOSE_DIR    dossier qui contient docker-compose.yml et .env (défaut : parent de scripts/)
# Code de sortie non nul en cas d'échec (le Planificateur de tâches du DSM peut alors envoyer un e-mail).
set -eu

# Planificateur de tâches du DSM : PATH minimal, docker est dans /usr/local/bin.
PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
umask 077 # le dump contient des empreintes de mots de passe : lisible par le seul propriétaire

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
BACKUP_DIR="${BACKUP_DIR:-$COMPOSE_DIR/backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
BACKUP_PREFIX="${BACKUP_PREFIX:-anime-db}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') backup-db: $*"; }
die() { log "ÉCHEC : $*" >&2; exit 1; }

case "$BACKUP_KEEP" in ''|*[!0-9]*) die "BACKUP_KEEP doit être un nombre (reçu : $BACKUP_KEEP)" ;; esac
[ "$BACKUP_KEEP" -ge 1 ] || die "BACKUP_KEEP doit valoir au moins 1"
case "$BACKUP_PREFIX" in *[!A-Za-z0-9_-]*|'') die "BACKUP_PREFIX : lettres, chiffres, - et _ seulement" ;; esac

cd "$COMPOSE_DIR" || die "dossier introuvable : $COMPOSE_DIR"
[ -f docker-compose.yml ] || die "pas de docker-compose.yml dans $COMPOSE_DIR (définir COMPOSE_DIR)"

if docker compose version >/dev/null 2>&1; then
    compose() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
    compose() { docker-compose "$@"; }
else
    die "ni 'docker compose' ni 'docker-compose' trouvé"
fi

mkdir -p "$BACKUP_DIR" || die "impossible de créer $BACKUP_DIR"
stamp=$(date '+%Y%m%d-%H%M%S')
final="$BACKUP_DIR/$BACKUP_PREFIX-$stamp.dump"
tmp="$final.part"
trap 'rm -f "$tmp"' EXIT INT TERM

log "sauvegarde vers $final"
# Variables lues dans l'environnement du conteneur (fourni par docker-compose.yml), pas ici.
compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=6' \
    > "$tmp" || die "pg_dump a échoué (la stack est-elle démarrée ? 'docker compose ps')"
[ -s "$tmp" ] || die "sauvegarde vide"
# Vérification : le fichier doit être lisible par pg_restore (sinon : sauvegarde tronquée).
compose exec -T postgres pg_restore --list < "$tmp" > /dev/null || die "sauvegarde illisible par pg_restore"
mv "$tmp" "$final"
trap - EXIT INT TERM
log "OK ($(du -h "$final" | cut -f1))"

# Rotation : on garde les BACKUP_KEEP plus récentes (les noms horodatés se trient dans l'ordre).
count=0
for f in $(ls -1 "$BACKUP_DIR" | grep -E "^$BACKUP_PREFIX-[0-9]{8}-[0-9]{6}\.dump$" | sort -r); do
    count=$((count + 1))
    if [ "$count" -gt "$BACKUP_KEEP" ]; then
        rm -f "$BACKUP_DIR/$f"
        log "ancienne sauvegarde supprimée : $f"
    fi
done
