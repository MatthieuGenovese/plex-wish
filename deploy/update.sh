#!/bin/sh
# Mise à jour d'Anime Server (D1.6), lancée par l'administrateur (Matthieu), jamais automatique.
# Dans le dossier du projet sur le NAS, en root :
#
#   sh app/update.sh 1.4.0           demande confirmation
#   sh app/update.sh 1.4.0 --yes     sans question (update.ps1 depuis le PC)
#
# 1. télécharge la nouvelle version (le site tourne encore) ;
# 2. sauvegarde la base et vérifie la sauvegarde (« avant-maj ») ;
# 3. redémarre sur la nouvelle version ;
# 4. attend que tout soit en bonne santé (serveur, web, et la chaîne web -> serveur) : 5 minutes au plus ;
# 5. en cas d'échec : revient à l'ancienne version et, si la base a été modifiée (migration), restaure la sauvegarde
#    de l'étape 2. Rien n'est perdu sauf ce qui a été fait PENDANT ces quelques minutes.
set -eu
cd "$(dirname "$0")/.."
. ./app/lib.sh

new=${1:-}
echo "$new" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "usage : sh app/update.sh <version, ex. 1.4.0> [--yes]"
old=$(env_get ANIME_VERSION)
prefix=$(env_get IMAGE_PREFIX)
[ "$new" != "$old" ] || die "la version $new est déjà installée"

if [ "${2:-}" != "--yes" ]; then
    printf "Mettre à jour de %s vers %s ? Taper OUI pour continuer : " "$old" "$new"
    read -r answer
    [ "$answer" = "OUI" ] || die "annulé"
fi

log "1/5 téléchargement de la version $new"
docker pull -q "$prefix/anime-server-backend:$new" > /dev/null || die "image du serveur $new introuvable (publiée ? jeton du registre valable ?)"
docker pull -q "$prefix/anime-server-web:$new" > /dev/null || die "image web $new introuvable"

log "2/5 sauvegarde de la base avant mise à jour"
compose up -d backup > /dev/null 2>&1
compose exec -T backup sh /app-scripts/backup-loop.sh once avant-maj > /dev/null || die "sauvegarde impossible : mise à jour annulée, rien n'a changé"
dump=$(ls -1t backups/avant-maj-*.dump | head -n 1)
schema_before=$(compose exec -T postgres psql -tAq -U anime -d anime -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")
log "    $dump (schéma $schema_before)"

log "3/5 passage à la version $new"
# Fichiers de déploiement de la nouvelle version (compose, scripts) ; les anciens sont gardés pour le retour arrière.
# Le dossier app/ est vidé puis rempli, jamais remplacé : les conteneurs qui le montent (backup) le voient toujours.
rm -rf app.prev app.new && cp -a app app.prev && cp compose.yml compose.yml.prev && cp nas.env nas.env.prev
mkdir app.new
docker run --rm --entrypoint tar "$prefix/anime-server-backend:$new" -C /app/deploy -cf - . | tar -C app.new -xf -
[ -s app.new/compose.yml ] || die "fichiers de déploiement absents de l'image $new"
find app -mindepth 1 -delete && cp -a app.new/. app/ && rm -rf app.new && cp app/compose.yml compose.yml
env_set ANIME_VERSION "$new"
env_set PREVIOUS_VERSION "$old"
compose up -d --remove-orphans > /dev/null 2>&1 || true

log "4/5 vérification de santé"
healthy=no
if wait_healthy backend 300 && wait_healthy web 120 \
    && compose exec -T web wget -q -O - http://127.0.0.1/api/setup/status 2> /dev/null | grep -q '"installed"'; then
    healthy=yes
fi

if [ "$healthy" = yes ]; then
    rm -rf app.prev compose.yml.prev nas.env.prev
    log "5/5 OK : version $new en service (ancienne : $old, sauvegarde : $dump)"
    exit 0
fi

log "5/5 ÉCHEC de la version $new : retour à la version $old"
compose logs --tail 40 backend > "backups/echec-maj-$new.log" 2>&1 || true
schema_after=$(compose exec -T postgres psql -tAq -U anime -d anime -c "SELECT max(version::int) FROM flyway_schema_history WHERE success" 2> /dev/null || echo "?")
find app -mindepth 1 -delete && cp -a app.prev/. app/ && rm -rf app.prev && mv compose.yml.prev compose.yml && mv nas.env.prev nas.env
if [ "$schema_after" != "$schema_before" ]; then
    log "    la base a été modifiée (schéma $schema_before -> $schema_after) : restauration de $dump"
    sh app/restore.sh "$dump" --yes || die "retour arrière INCOMPLET : restaurer à la main $dump (sh app/restore.sh)"
else
    compose up -d --remove-orphans > /dev/null 2>&1 || true
    wait_healthy backend 300 || die "retour arrière INCOMPLET : la version $old ne redémarre pas (docker compose logs)"
fi
log "    retour arrière terminé : version $old en service. Journal de l'échec : backups/echec-maj-$new.log"
exit 1
