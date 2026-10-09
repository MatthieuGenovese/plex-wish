#!/bin/bash
# ESSAI DE BOUT EN BOUT du déploiement, sur une pile Docker propre (D1). Utilise les images du registre local
# (scripts/test/build-test-images.sh). Tout est créé dans un dossier jetable et supprimé à la fin.
#
#   scripts/test/e2e-deploy.sh [étape…]     (sans argument : toutes les étapes)
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
E2E=${E2E_DIR:-/tmp/claude-0/e2e}
DATA=$E2E/data
MEDIA=$E2E/media
REGISTRY=${REGISTRY:-localhost:5000/test}
VERSION=${VERSION:-1.0.0}
LAN_PORT=18080
SUBNET=172.31.99.0/24
pass=0
ok() { echo "  ✔ $*"; pass=$((pass + 1)); }
fail() { echo "  ✘ $*" >&2; exit 1; }
dc() { (cd "$DATA" && docker compose --env-file nas.env -f compose.yml "$@"); }
# Toutes les valeurs de secrets de la pile (pour vérifier qu'elles n'apparaissent nulle part).
secret_values() { docker run --rm -v anime-server_secrets_app:/s:ro alpine:3 sh -c 'cat /s/keys/* 2>/dev/null; echo'; }

clean() {
    (cd "$DATA" 2>/dev/null && docker compose --env-file nas.env -f compose.yml down -v --remove-orphans > /dev/null 2>&1) || true
    docker volume rm -f anime-server_pgdata anime-server_secrets_app anime-server_secrets_pg > /dev/null 2>&1 || true
    rm -rf "$E2E"
}

setup() {
    clean
    mkdir -p "$DATA/app" "$MEDIA/Frieren/Saison 1"
    # Médias appartenant à un utilisateur du NAS (1026:100, lisibles par lui et son groupe seulement).
    head -c 2048 /dev/urandom > "$MEDIA/Frieren/Saison 1/Frieren S01E01.mkv"
    chown -R 1026:100 "$MEDIA" && chmod -R u=rwX,g=rX,o= "$MEDIA"
    cp "$root"/deploy/*.sh "$DATA/app/"
    cp "$root/deploy/compose.yml" "$DATA/compose.yml"
    cat > "$DATA/nas.env" <<ENV
ANIME_VERSION=$VERSION
IMAGE_PREFIX=$REGISTRY
MEDIA_PATH=$MEDIA
PUBLIC_URL=https://anime.e2e.test
LAN_PORT=$LAN_PORT
TZ=Europe/Paris
DOCKER_SUBNET=$SUBNET
ENV
}

step_first_start() {
    echo "== Premier démarrage : secrets créés, droits, utilisateur du serveur"
    dc up -d > /dev/null 2>&1 || { dc logs init backend | tail -30; fail "démarrage"; }
    dc logs init | grep -q "secret jwt_secret créé" && ok "secrets créés par init" || fail "secrets non créés"
    dc logs init | grep -q "utilisateur 1026:100" && ok "utilisateur du dossier média détecté (1026:100)" || fail "utilisateur : $(dc logs init | grep utilisateur)"
    perms=$(docker run --rm -v anime-server_secrets_app:/s:ro -v anime-server_secrets_pg:/p:ro alpine:3 \
        sh -c 'stat -c "%n %a %u" /s/keys /s/keys/* /p /p/db_password /s/runtime-user')
    echo "$perms" | grep -q "/s/keys 700 1026" && echo "$perms" | grep -q "/s/keys/jwt_secret 600 1026" \
        && echo "$perms" | grep -q "/p/db_password 600 70" && echo "$perms" | grep -q "/s/runtime-user 644 0" \
        && ok "droits : keys 700, secrets 600 au serveur, copie postgres 600 à postgres" || fail "droits : $perms"
    [ "$(secret_values | awk 'length($0)==64' | wc -l)" = 3 ] && ok "3 secrets de 64 caractères" || fail "longueur des secrets"
    for i in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q backend)")" = healthy ] && break; sleep 3; done
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q backend)")" = healthy ] && ok "serveur en bonne santé" || { dc logs backend | tail -20; fail "serveur"; }
    [ "$(docker top "$(dc ps -q backend)" -eo uid,pid,args | grep java | awk "{print \$1}")" = 1026 ] && ok "serveur sans root (uid 1026)" || fail "utilisateur java : $(docker top "$(dc ps -q backend)" -eo uid,pid,args)"
    env=$(docker inspect -f '{{json .Config.Env}}' "$(dc ps -q backend)" "$(dc ps -q postgres)")
    leaks=0
    while read -r v; do [ -n "$v" ] || continue
        if dc logs 2>&1 | grep -qF "$v" || echo "$env" | grep -qF "$v"; then leaks=$((leaks + 1)); fi
    done < <(secret_values)
    [ "$leaks" = 0 ] && ok "aucun secret dans les journaux ni dans l'environnement des conteneurs" || fail "$leaks secret(s) visibles"
    # Redémarrage : les secrets ne changent pas.
    before=$(secret_values | md5sum)
    dc up -d --force-recreate > /dev/null 2>&1
    [ "$(secret_values | md5sum)" = "$before" ] && ok "secrets inchangés après redémarrage" || fail "secrets régénérés"
}

step_secrets_lost() {
    echo "== Volume des secrets perdu alors que la base existe : détection, aucune donnée effacée, réparation"
    for i in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q backend)")" = healthy ] && break; sleep 3; done
    dc exec -T postgres psql -q -U anime -d anime -c "CREATE TABLE e2e_marker(v text); INSERT INTO e2e_marker VALUES ('intact');"
    dc down > /dev/null 2>&1
    docker volume rm anime-server_secrets_app anime-server_secrets_pg > /dev/null
    if dc up -d > /dev/null 2>&1; then fail "la pile a démarré sans secrets"; fi
    dc logs init | grep -q "les secrets ont disparu, mais la base de données existe encore" && ok "init refuse et explique" || fail "message absent"
    [ -z "$(dc ps -q backend)" ] || [ "$(docker inspect -f '{{.State.Running}}' "$(dc ps -q backend)")" = false ] && ok "serveur non démarré" || fail "serveur démarré"
    (cd "$DATA" && sh app/repair-db-password.sh --yes) > "$E2E/repair.log" 2>&1 || { cat "$E2E/repair.log"; fail "réparation"; }
    grep -q "OK : la base est intacte" "$E2E/repair.log" && ok "réparation terminée, serveur en bonne santé" || fail "réparation"
    [ "$(dc exec -T postgres psql -tA -U anime -d anime -c 'SELECT v FROM e2e_marker')" = intact ] && ok "données intactes" || fail "données"
    leaks=0
    while read -r v; do [ -n "$v" ] || continue; grep -qF "$v" "$E2E/repair.log" && leaks=$((leaks + 1)); done < <(secret_values)
    [ "$leaks" = 0 ] && ok "nouveau mot de passe jamais affiché" || fail "mot de passe affiché"
}

steps=${*:-"first_start secrets_lost"}
setup
for s in $steps; do "step_$s"; done
[ "${KEEP:-0}" = 1 ] || clean
echo "== $pass vérifications réussies"
