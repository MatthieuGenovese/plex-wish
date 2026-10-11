#!/bin/sh
# Sauvegardes automatiques de la base (D1.5), dans le conteneur « backup » (image postgres:16-alpine : mêmes outils
# que le serveur de base). Aucune tâche du DSM : la boucle tourne dans le conteneur.
#
#   backup-loop.sh                     boucle : une sauvegarde chaque nuit à BACKUP_HOUR (3 h par défaut)
#   backup-loop.sh once [préfixe]      une sauvegarde tout de suite (update.sh : « avant-maj », restore.sh : « avant-restauration »)
#   backup-loop.sh rotate              ne garde que la sauvegarde la plus récente (essais)
#
# Chaque sauvegarde est VÉRIFIÉE : restaurée dans une base temporaire (puis supprimée), et la version du schéma
# comparée à celle de la base. Une fois vérifiée, **elle seule est gardée** : toutes les autres (de la nuit, avant mise
# à jour, avant restauration, invalides) sont supprimées (choix du 2026-10-11 : seule la dernière compte). Une
# sauvegarde ratée ne supprime rien. Exception : la sauvegarde « avant-restauration » ne supprime rien, le fichier
# qu'on s'apprête à restaurer doit rester (le ménage se fait à la sauvegarde suivante). État dans status.json.
# Le mot de passe de la base est lu dans le fichier créé par init, jamais affiché.
set -eu

DIR=${BACKUP_DIR:-/backups}
HOUR=${BACKUP_HOUR:-3}
export PGHOST=${PGHOST:-postgres} PGUSER=${PGUSER:-anime} PGDATABASE=${PGDATABASE:-anime}
if [ -z "${PGPASSWORD:-}" ] && [ -r /run/secrets/pg/db_password ]; then
    PGPASSWORD=$(cat /run/secrets/pg/db_password); export PGPASSWORD
fi
umask 077 # une sauvegarde contient les empreintes des mots de passe : lisible par root seulement

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') sauvegarde: $*"; }

status() { # status <ok> <vérifiée> <fichier> <taille> <message>
    tmp="$DIR/.status.tmp"
    printf '{"lastRun":"%s","ok":%s,"verified":%s,"file":"%s","sizeBytes":%s,"message":"%s"}\n' \
        "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$2" "$3" "$4" "$5" > "$tmp"
    chmod 644 "$tmp"
    mv "$tmp" "$DIR/status.json"
}

schema_version() { psql -tAq -d "$1" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success" 2>/dev/null || echo "?"; }

backup_once() {
    prefix=${1:-anime-db}
    case "$prefix" in *[!A-Za-z0-9-]*|'') log "préfixe invalide"; return 2 ;; esac
    mkdir -p "$DIR"
    name="$prefix-$(date '+%Y%m%d-%H%M%S').dump"
    part="$DIR/.$name.part"
    log "début : $name"
    if ! pg_dump --format=custom --compress=6 -f "$part"; then
        rm -f "$part"; log "ÉCHEC de pg_dump"; status false false "$name" 0 "pg_dump a échoué"; return 1
    fi
    # Vérification : restauration complète dans une base jetable.
    verify="anime_verify_$$"
    ok=true
    message="restaurée et vérifiée"
    dropdb --if-exists "$verify" 2> /dev/null || true
    if ! createdb "$verify" || ! pg_restore --no-owner --exit-on-error -d "$verify" "$part" > /dev/null 2>&1; then
        ok=false; message="la restauration d'essai a échoué"
    else
        live=$(schema_version "$PGDATABASE"); copy=$(schema_version "$verify")
        if [ "$live" != "$copy" ] || [ "$copy" = "?" ]; then ok=false; message="version du schéma différente ($copy au lieu de $live)"; fi
    fi
    dropdb --if-exists "$verify" 2> /dev/null || true
    if [ "$ok" != true ]; then
        mv "$part" "$DIR/$name.invalide"
        log "ÉCHEC de la vérification : $message"
        status false false "$name" 0 "$message"
        return 1
    fi
    mv "$part" "$DIR/$name"
    size=$(stat -c %s "$DIR/$name")
    log "OK : $name ($size octets, $message)"
    status true true "$name" "$size" "$message"
    if [ "$prefix" != avant-restauration ]; then
        keep_only "$name"
    fi
}

# Ménage : supprime toutes les sauvegardes (.dump, .dump.invalide) sauf celle nommée.
keep_only() {
    for f in $(ls -1 "$DIR" 2> /dev/null | grep -E '\.dump(\.invalide)?$'); do
        if [ "$f" != "$1" ]; then
            rm -f "$DIR/$f"; log "ménage : $f supprimée"
        fi
    done
}

# Garde seulement la sauvegarde la plus récente (date du fichier).
rotate() {
    newest=$(ls -1t "$DIR" 2> /dev/null | grep -E '\.dump$' | head -n 1 || true)
    [ -n "$newest" ] && keep_only "$newest"
    return 0
}

case "${1:-loop}" in
    once) backup_once "${2:-anime-db}"; exit $? ;;
    rotate) rotate; exit 0 ;;
    loop) ;;
    *) echo "usage : $0 [loop|once [préfixe]|rotate]" >&2; exit 2 ;;
esac

log "sauvegarde automatique chaque nuit à ${HOUR} h (dossier : $DIR)"
# Rattrapage : pas de sauvegarde depuis plus de 26 h (NAS éteint la nuit, premier démarrage) : une tout de suite.
last=$(ls -1t "$DIR"/anime-db-*.dump 2> /dev/null | head -n 1 || true)
if [ -z "$last" ] || [ $(( $(date +%s) - $(stat -c %Y "$last") )) -gt 93600 ]; then
    sleep 60 # laisser le serveur appliquer ses migrations au premier démarrage
    backup_once || true
fi
while true; do
    now=$(date +%s)
    next=$(date -d "$(date '+%Y-%m-%d') $(printf '%02d' "$HOUR"):00:00" +%s)
    [ "$next" -le "$now" ] && next=$((next + 86400))
    sleep $((next - now))
    backup_once || true
done
