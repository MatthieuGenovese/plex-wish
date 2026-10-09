#!/bin/sh
# Sauvegardes automatiques de la base (D1.5), dans le conteneur « backup » (image postgres:16-alpine : mêmes outils
# que le serveur de base). Aucune tâche du DSM : la boucle tourne dans le conteneur.
#
#   backup-loop.sh                     boucle : une sauvegarde chaque nuit à BACKUP_HOUR (3 h par défaut)
#   backup-loop.sh once [préfixe]      une sauvegarde tout de suite (update.sh : « avant-maj », restore.sh : « avant-restauration »)
#   backup-loop.sh rotate              rotation seule (essais)
#
# Chaque sauvegarde est VÉRIFIÉE : restaurée dans une base temporaire (puis supprimée), et la version du schéma
# comparée à celle de la base. Rotation : 7 quotidiennes, 4 hebdomadaires, 6 mensuelles ; les sauvegardes de sécurité
# (avant mise à jour, avant restauration) : les 5 dernières de chaque sorte. État dans status.json (lu par le serveur).
# Le mot de passe de la base est lu dans le fichier créé par init, jamais affiché.
set -eu

DIR=${BACKUP_DIR:-/backups}
HOUR=${BACKUP_HOUR:-3}
KEEP_DAILY=${KEEP_DAILY:-7}
KEEP_WEEKLY=${KEEP_WEEKLY:-4}
KEEP_MONTHLY=${KEEP_MONTHLY:-6}
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
    rotate
}

# Rotation : du plus récent au plus ancien, une sauvegarde par jour (7 jours), puis la plus récente de chaque semaine
# pas encore représentée (4), puis de chaque mois pas encore représenté (6). Seules les sauvegardes gardées
# « représentent » leur jour, leur semaine et leur mois.
rotate() {
    days=""; weeks=""; months=""; nd=0; nw=0; nm=0
    for f in $(ls -1 "$DIR" 2> /dev/null | grep -E '^anime-db-[0-9]{8}-[0-9]{6}\.dump$' | sort -r); do
        d=$(echo "$f" | cut -c10-17)
        iso="$(echo "$d" | cut -c1-4)-$(echo "$d" | cut -c5-6)-$(echo "$d" | cut -c7-8)"
        w=$(date -d "$iso" '+%G%V' 2> /dev/null || echo "$d")
        m=$(echo "$d" | cut -c1-6)
        keep=no
        case " $days " in *" $d "*) ;; *)
            if [ "$nd" -lt "$KEEP_DAILY" ]; then
                keep=yes; nd=$((nd + 1))
            else
                case " $weeks " in *" $w "*) ;; *)
                    if [ "$nw" -lt "$KEEP_WEEKLY" ]; then keep=yes; nw=$((nw + 1)); fi ;;
                esac
                if [ "$keep" = no ]; then
                    case " $months " in *" $m "*) ;; *)
                        if [ "$nm" -lt "$KEEP_MONTHLY" ]; then keep=yes; nm=$((nm + 1)); fi ;;
                    esac
                fi
            fi ;;
        esac
        if [ "$keep" = yes ]; then
            days="$days $d"; weeks="$weeks $w"; months="$months $m"
        else
            rm -f "$DIR/$f"; log "rotation : $f supprimée"
        fi
    done
    for p in avant-maj avant-restauration; do
        ls -1 "$DIR" 2> /dev/null | grep -E "^$p-[0-9]{8}-[0-9]{6}\.dump$" | sort -r | tail -n +6 | while read -r f; do
            rm -f "$DIR/$f"; log "rotation : $f supprimée"
        done
    done
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
