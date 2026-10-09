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
PUBLIC_PORT=18443
DOMAIN=anime.e2e.test
SUBNET=172.31.99.0/24
LAN="http://localhost:$LAN_PORT"
PUB="https://$DOMAIN:$PUBLIC_PORT"
# Porte publique : Caddy en HTTPS (certificat interne de Caddy pour l'essai : -k), nom résolu vers cette machine.
pub() { curl -sk --noproxy "*" --resolve "$DOMAIN:$PUBLIC_PORT:127.0.0.1" "$@"; }
lan() { curl -s --noproxy "*" "$@"; }
json() { python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"; }
wait_backend() {
    for i in $(seq 1 80); do
        [ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q backend)" 2>/dev/null)" = healthy ] && return 0; sleep 3
    done
    dc logs backend | tail -30; fail "serveur pas en bonne santé"
}
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
    # Essai : certificat interne de Caddy au lieu de Let's Encrypt (pas de vrai nom de domaine ici).
    sed 's/issuer acme {/issuer internal {/; /disable_http_challenge/d' "$root/deploy/Caddyfile" > "$DATA/app/Caddyfile"
    cat > "$DATA/nas.env" <<ENV
ANIME_VERSION=$VERSION
IMAGE_PREFIX=$REGISTRY
MEDIA_PATH=$MEDIA
PUBLIC_DOMAIN=$DOMAIN
COMPOSE_PROFILES=caddy
PUBLIC_PORT=$PUBLIC_PORT
LAN_PORT=$LAN_PORT
TZ=Europe/Paris
DOCKER_SUBNET=$SUBNET
ENV
}

step_first_start() {
    echo "== Premier démarrage : secrets créés, droits, utilisateur du serveur"
    dc up -d > /dev/null 2>&1 || { dc logs init backend | tail -30; fail "démarrage"; }
    grep -q "secret jwt_secret créé" <<< "$(dc logs init)" && ok "secrets créés par init" || fail "secrets non créés"
    grep -q "utilisateur 1026:100" <<< "$(dc logs init)" && ok "utilisateur du dossier média détecté (1026:100)" || fail "utilisateur : $(dc logs init | grep utilisateur)"
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
        if grep -qF "$v" <<< "$(dc logs 2>&1)" || grep -qF "$v" <<< "$env"; then leaks=$((leaks + 1)); fi
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
    grep -q "les secrets ont disparu, mais la base de données existe encore" <<< "$(dc logs init)" && ok "init refuse et explique" || fail "message absent"
    [ -z "$(dc ps -q backend)" ] || [ "$(docker inspect -f '{{.State.Running}}' "$(dc ps -q backend)")" = false ] && ok "serveur non démarré" || fail "serveur démarré"
    (cd "$DATA" && sh app/repair-db-password.sh --yes) > "$E2E/repair.log" 2>&1 || { cat "$E2E/repair.log"; fail "réparation"; }
    grep -q "OK : la base est intacte" "$E2E/repair.log" && ok "réparation terminée, serveur en bonne santé" || fail "réparation"
    [ "$(dc exec -T postgres psql -tA -U anime -d anime -c 'SELECT v FROM e2e_marker')" = intact ] && ok "données intactes" || fail "données"
    leaks=0
    while read -r v; do [ -n "$v" ] || continue; grep -qF "$v" "$E2E/repair.log" && leaks=$((leaks + 1)); done < <(secret_values)
    [ "$leaks" = 0 ] && ok "nouveau mot de passe jamais affiché" || fail "mot de passe affiché"
}

step_wizard() {
    echo "== Assistant de premier lancement : réseau local seulement, Internet en attente, puis site ouvert"
    wait_backend
    for i in $(seq 1 20); do pub -o /dev/null "$PUB/" && break; sleep 2; done
    [ "$(pub "$PUB/api/setup/status" | json "d['installed'], d['entry']")" = "False public" ] \
        && ok "par Internet (Caddy, HTTPS) : installation non terminée, porte publique" || fail "statut public"
    [ "$(pub -o /dev/null -w '%{http_code}' "$PUB/api/anime")" = 503 ] && ok "par Internet : le site répond 503 « installation en cours »" || fail "503"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"username":"pirate","password":"0123456789abc"}' "$PUB/api/setup/admin")" = 403 ] \
        && ok "par Internet : création de l'admin refusée" || fail "admin par Internet"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'X-Anime-Entry: lan' -H 'Content-Type: application/json' -d '{"username":"pirate","password":"0123456789abc"}' "$PUB/api/setup/admin")" = 403 ] \
        && ok "en-tête X-Anime-Entry falsifié par le visiteur : ignoré" || fail "en-tête falsifié accepté"
    grep -qi '^strict-transport-security: max-age=31536000' <<< "$(pub -sI "$PUB/")" && ok "HTTPS imposé (HSTS)" || fail "HSTS absent"
    checks=$(lan "$LAN/api/setup/checks" | json "' '.join(c['id'] + ':' + c['state'] for c in d)")
    echo "$checks" | grep -q "media:OK" && echo "$checks" | grep -q "posters:OK" && echo "$checks" | grep -q "ffmpeg:OK" \
        && ok "vérifications depuis le réseau local : $checks" || fail "vérifications : $checks"
    token=$(lan -H 'Content-Type: application/json' -d '{"username":"chef","password":"mot-de-passe-du-chef"}' "$LAN/api/setup/admin" | json "d['accessToken']")
    [ -n "$token" ] && ok "compte administrateur créé par l'assistant, mot de passe choisi" || fail "admin"
    [ "$(lan -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"username":"autre","password":"mot-de-passe-du-chef"}' "$LAN/api/setup/admin")" = 409 ] \
        && ok "second administrateur refusé" || fail "second admin"
    proposed=$(lan -H "Authorization: Bearer $token" "$LAN/api/setup/disk" | json "d['proposed']")
    lan -o /dev/null -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        -d "$(echo "$proposed" | tr "'" '"')" "$LAN/api/setup/disk"
    [ "$(lan -H "Authorization: Bearer $token" "$LAN/api/setup/disk" | json "d['saved']")" = True ] && ok "seuils d'espace disque proposés et enregistrés ($proposed)" || fail "disque"
    lan -o /dev/null -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        -d '{"token":"eyJhbGciOiJIUzI1NiJ9.cle-tmdb-e2e-0123456789abcdef"}' "$LAN/api/setup/tmdb"
    perms=$(docker run --rm -v anime-server_secrets_app:/s:ro alpine:3 stat -c "%a %u" /s/keys/tmdb_token)
    [ "$perms" = "600 1026" ] && ok "clé TMDB rangée en secret (600, utilisateur du serveur)" || fail "clé TMDB : $perms"
    [ "$(lan -X POST -H "Authorization: Bearer $token" "$LAN/api/setup/finish" | json "d['installed']")" = True ] && ok "installation terminée" || fail "fin"
    [ "$(pub -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$PUB/api/anime")" = 200 ] && ok "par Internet : le site répond" || fail "site public"
    [ "$(lan -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$LAN/api/anime")" = 403 ] && ok "adresse locale : renvoie vers l'adresse publique" || fail "porte locale"
    [ "$(lan -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"username":"tard","password":"mot-de-passe-du-chef"}' "$LAN/api/setup/admin")" = 404 ] \
        && ok "assistant fermé définitivement" || fail "assistant encore ouvert"
    login=$(pub -o /dev/null -w '%{http_code}' -c "$E2E/cookies" -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d '{"login":"chef","password":"mot-de-passe-du-chef"}' "$PUB/api/auth/login")
    [ "$login" = 200 ] && grep -q refresh_token "$E2E/cookies" && ok "connexion par Internet avec cookie de session sécurisé" || fail "connexion publique : $login"
    leaks=0
    while read -r v; do [ -n "$v" ] || continue; grep -qF "$v" <<< "$(dc logs 2>&1)" && leaks=$((leaks + 1)); done < <(secret_values)
    [ "$leaks" = 0 ] && ok "aucun secret (clé TMDB comprise) dans les journaux" || fail "$leaks secret(s) dans les journaux"
}

steps=${*:-"first_start secrets_lost wizard"}
setup
for s in $steps; do "step_$s"; done
[ "${KEEP:-0}" = 1 ] || clean
echo "== $pass vérifications réussies"
