#!/bin/sh
# Conteneur « init » : prépare secrets et dossiers au démarrage de la stack, puis s'arrête (D1.2).
# Lancé en root, avant postgres et le serveur, à chaque démarrage. Idempotent : un secret existant n'est jamais
# remplacé. Aucune valeur de secret n'est jamais affichée ni journalisée.
#
#   init.sh                     démarrage normal
#   init.sh repair-db-password  nouveau mot de passe pour la base (voir repair-db-password.sh, ne touche pas aux données)
#
# Volumes attendus :
#   /secrets/app   secrets du serveur : runtime-user (qui fait tourner le serveur) et keys/ (un fichier par secret)
#   /secrets/pg    copie du mot de passe de la base pour le conteneur postgres
#   /pgdata        données de la base, en lecture seule (seulement pour savoir si la base existe déjà)
#   /media         dossier des vidéos, en lecture seule
#   /data/*        dossiers d'écriture du serveur (affiches, cache de remux…)
set -eu
umask 077

mode=${1:-init}
APP=/secrets/app
KEYS=$APP/keys
PG=/secrets/pg
PG_UID=70 # utilisateur « postgres » de l'image postgres:16-alpine

log() { echo "init: $*"; }

# 64 caractères aléatoires (lettres et chiffres), depuis le générateur du noyau.
generate() { head -c 192 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-64; }

# --- 1. Utilisateur qui fera tourner le serveur ---------------------------------------------------------
# Le premier qui sait lire le dossier média : celui imposé dans nas.env (MEDIA_UID/MEDIA_GID), sinon le
# propriétaire du dossier, puis celui d'un fichier dedans, puis les valeurs habituelles d'un NAS Synology
# (1026:100, premier utilisateur créé, groupe « users ») et 1000:1000. Jamais root.
can_read_media() {
    setpriv --reuid="$1" --regid="$2" --clear-groups -- sh -c '
        ls /media > /dev/null 2>&1 || exit 1
        f=$(find /media -maxdepth 3 -type f -size +0 2>/dev/null | head -n 1)
        [ -z "$f" ] || head -c 1 "$f" > /dev/null 2>&1' 2>/dev/null
}
candidates=""
[ -n "${MEDIA_UID:-}" ] && candidates="$MEDIA_UID:${MEDIA_GID:-$MEDIA_UID}"
candidates="$candidates $(stat -c '%u:%g' /media 2>/dev/null || true)"
first=$(find /media -mindepth 1 -maxdepth 2 2>/dev/null | head -n 1)
[ -n "$first" ] && candidates="$candidates $(stat -c '%u:%g' "$first")"
candidates="$candidates 1026:100 1000:1000"
chosen=""
for c in $candidates; do
    uid=${c%%:*}; gid=${c##*:}
    case "$uid:$gid" in *[!0-9:]*|:*|*:) continue ;; esac
    [ "$uid" = 0 ] && continue
    if can_read_media "$uid" "$gid"; then chosen="$uid:$gid"; break; fi
done
if [ -z "$chosen" ]; then
    chosen=${MEDIA_UID:+$MEDIA_UID:${MEDIA_GID:-$MEDIA_UID}}
    chosen=${chosen:-1000:1000}
    log "ATTENTION : aucun utilisateur ne peut lire le dossier média ; le serveur tournera avec $chosen."
    log "            L'assistant de premier lancement l'indiquera, avec la marche à suivre (droits du dossier)."
fi
uid=${chosen%%:*}; gid=${chosen##*:}

mkdir -p "$KEYS" "$PG"
chown 0:0 "$APP"; chmod 755 "$APP"
echo "$chosen" > "$APP/runtime-user.tmp"
chmod 644 "$APP/runtime-user.tmp"
mv "$APP/runtime-user.tmp" "$APP/runtime-user"
log "serveur lancé avec l'utilisateur $chosen (lecture du dossier média)"

# --- 2. Secrets -------------------------------------------------------------------------------------------
write_secret() { # write_secret <fichier> : valeur lue sur l'entrée standard, écriture atomique
    cat > "$1.tmp"
    mv "$1.tmp" "$1"
}
for name in jwt_secret stream_signing_secret; do
    if [ ! -s "$KEYS/$name" ]; then
        generate | write_secret "$KEYS/$name"
        log "secret $name créé"
    fi
done

if [ "$mode" = "repair-db-password" ]; then
    generate | write_secret "$KEYS/db_password"
    cp "$KEYS/db_password" "$PG/db_password.tmp" && mv "$PG/db_password.tmp" "$PG/db_password"
    log "nouveau mot de passe de la base créé (réparation) ; il reste à l'appliquer à la base (repair-db-password.sh)"
elif [ -s "$KEYS/db_password" ]; then
    if [ ! -s "$PG/db_password" ] || ! cmp -s "$KEYS/db_password" "$PG/db_password"; then
        cp "$KEYS/db_password" "$PG/db_password.tmp" && mv "$PG/db_password.tmp" "$PG/db_password"
        log "copie du mot de passe de la base pour postgres rétablie"
    fi
elif [ -s "$PG/db_password" ]; then
    cp "$PG/db_password" "$KEYS/db_password.tmp" && mv "$KEYS/db_password.tmp" "$KEYS/db_password"
    log "mot de passe de la base du serveur rétabli depuis la copie de postgres"
elif [ -s /pgdata/PG_VERSION ]; then
    cat >&2 <<'MSG'
init: ÉCHEC : les secrets ont disparu, mais la base de données existe encore.
init:
init:   Le mot de passe de la base était rangé avec les secrets (volume Docker « secrets »). Ce volume a été
init:   supprimé (par exemple par « docker compose down -v ») alors que la base, elle, est toujours là.
init:   Un nouveau mot de passe ne correspondrait pas à celui que la base connaît : le serveur ne pourrait pas s'y
init:   connecter. Rien n'a été modifié, la base et ses données sont intactes.
init:
init:   Réparation sûre (aucune donnée effacée) : dans le dossier du projet sur le NAS, lancer
init:       sh app/repair-db-password.sh
init:   Le script crée un nouveau mot de passe, l'applique à la base existante, puis redémarre tout.
init:   Les sessions ouvertes seront à rouvrir (les autres secrets ont aussi été recréés).
MSG
    exit 3
else
    generate | write_secret "$KEYS/db_password"
    cp "$KEYS/db_password" "$PG/db_password.tmp" && mv "$PG/db_password.tmp" "$PG/db_password"
    log "secret db_password créé"
fi

# Droits : secrets du serveur à son seul utilisateur, copie de la base au seul utilisateur postgres.
chown "$uid:$gid" "$KEYS" && chmod 700 "$KEYS"
find "$KEYS" -type f -exec chown "$uid:$gid" {} + -exec chmod 600 {} +
chown "$PG_UID:$PG_UID" "$PG" "$PG/db_password" && chmod 700 "$PG" && chmod 600 "$PG/db_password"

# --- 3. Dossiers d'écriture du serveur ----------------------------------------------------------------------
for d in /data/*; do
    [ -d "$d" ] || continue
    chown "$uid:$gid" "$d"
    # Fichiers d'une installation précédente avec un autre utilisateur : rendus au serveur.
    find "$d" ! -user "$uid" -exec chown "$uid:$gid" {} + 2>/dev/null || true
done

log "prêt"
