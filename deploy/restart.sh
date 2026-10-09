#!/bin/sh
# Redémarre Anime Server en relisant nas.env (après une modification des réglages). Dans le dossier du projet, en root :
#   sh app/restart.sh
set -eu
cd "$(dirname "$0")/.."
. ./app/lib.sh
compose up -d --remove-orphans
wait_healthy backend 300 || die "le serveur ne redémarre pas (voir : docker compose --env-file nas.env -f compose.yml logs backend)"
log "OK"
