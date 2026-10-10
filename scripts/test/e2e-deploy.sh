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
    mkdir -p "$E2E" "$MEDIA/Frieren/Saison 1"
    # Médias appartenant à un utilisateur du NAS (1026:100, lisibles par lui et son groupe seulement).
    head -c 2048 /dev/urandom > "$MEDIA/Frieren/Saison 1/Frieren S01E01.mkv"
    chown -R 1026:100 "$MEDIA" && chmod -R u=rwX,g=rX,o= "$MEDIA"
    # La commande de l'ami, telle que la génère make-install-command (sans « docker login » : registre local).
    args="--version $VERSION --registry $REGISTRY --media $MEDIA --domain $DOMAIN --data $DATA --lan-port $LAN_PORT --public-port $PUBLIC_PORT"
    docker run --rm --entrypoint cat "$REGISTRY/anime-server-backend:$VERSION" /app/deploy/install.sh > "$E2E/anime-install.sh"
    # shellcheck disable=SC2086
    sh "$E2E/anime-install.sh" $args --dry-run > "$E2E/dry.log" 2>&1 || { cat "$E2E/dry.log"; fail "simulation"; }
    [ ! -e "$DATA" ] && grep -q "SIMULATION : rien ne sera modifié" "$E2E/dry.log" \
        && ok "simulation (--dry-run) : étapes affichées, rien créé" || fail "la simulation a modifié quelque chose"
    # Accès aux images vérifié dès la simulation : une version absente du registre est refusée avec un message clair.
    # shellcheck disable=SC2086
    if sh "$E2E/anime-install.sh" $(echo "$args" | sed "s/--version $VERSION/--version 9.9.9/") --dry-run > "$E2E/dry-bad.log" 2>&1; then
        fail "version absente acceptée"; fi
    grep -q "version 9.9.9 introuvable" "$E2E/dry-bad.log" && ok "version absente du registre : refusée dès la simulation" || { cat "$E2E/dry-bad.log"; fail "message"; }
    # Plage réseau par défaut déjà prise par un autre projet Docker (cas vécu sur un PC d'essai) : une autre est choisie.
    docker network rm e2e-occupe > /dev/null 2>&1 || true
    docker network create --subnet 172.30.64.0/24 e2e-occupe > /dev/null
    # shellcheck disable=SC2086
    sh "$E2E/anime-install.sh" $args > "$E2E/install.log" 2>&1 || { tail -30 "$E2E/install.log"; docker network rm e2e-occupe > /dev/null; fail "installation"; }
    docker network rm e2e-occupe > /dev/null
    chosen=$(sed -n 's/^DOCKER_SUBNET=//p' "$DATA/nas.env")
    [ -n "$chosen" ] && [ "$chosen" != 172.30.64.0/24 ] && docker network inspect anime-server_default > /dev/null 2>&1 \
        && ok "plage réseau déjà prise : une plage libre choisie ($chosen)" || fail "plage réseau : '$chosen'"
    grep -q "Ouvrir dans un navigateur, depuis la maison : http://.*:$LAN_PORT" "$E2E/install.log" && ok "installation en une commande, adresse de l'assistant affichée" \
        || { cat "$E2E/install.log"; fail "fin d'installation"; }
    # Essai : certificat interne de Caddy au lieu de Let's Encrypt (pas de vrai nom de domaine ici).
    sed 's/issuer acme {/issuer internal {/; /disable_http_challenge/d' "$DATA/app/Caddyfile" > "$DATA/Caddyfile.e2e"
    echo "CADDYFILE=./Caddyfile.e2e" >> "$DATA/nas.env"
    dc up -d caddy > /dev/null 2>&1
    # Relance de l'installation : rien ne casse, réglages gardés.
    # shellcheck disable=SC2086
    sh "$E2E/anime-install.sh" $args > "$E2E/install2.log" 2>&1 && grep -q "nas.env existe déjà : gardé tel quel" "$E2E/install2.log" \
        && ok "relancer la commande ne casse rien (réglages gardés)" || { cat "$E2E/install2.log"; fail "relance"; }
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

step_invitation() {
    echo "== Invitation : lien à usage unique, la personne choisit son mot de passe"
    token=$(pub -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" -d '{"login":"chef","password":"mot-de-passe-du-chef"}' \
        "$PUB/api/auth/login" | json "d['accessToken']")
    url=$(pub -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d '{"username":"alice","role":"USER"}' "$PUB/api/admin/users" | json "d['invitation']['url']")
    case "$url" in "https://$DOMAIN/invitation#"*) ok "lien d'invitation : $DOMAIN/invitation#…" ;; *) fail "lien : $url" ;; esac
    jeton=${url#*#}
    [ "$(pub -H 'Content-Type: application/json' -d "{\"token\":\"$jeton\"}" "$PUB/api/invitation/check" | json "d['username']")" = alice ] \
        && ok "le lien montre à qui il est destiné" || fail "check"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d "{\"token\":\"$jeton\",\"password\":\"le-mot-de-passe-d-alice\"}" "$PUB/api/invitation/accept")" = 200 ] \
        && ok "mot de passe choisi par la personne" || fail "accept"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d "{\"token\":\"$jeton\",\"password\":\"un-autre-mot-de-passe\"}" "$PUB/api/invitation/accept")" = 410 ] \
        && ok "lien inutilisable une seconde fois" || fail "réutilisation"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d '{"login":"alice","password":"le-mot-de-passe-d-alice"}' "$PUB/api/auth/login")" = 200 ] && ok "alice se connecte" || fail "connexion alice"
    grep -qF "$jeton" <<< "$(dc logs 2>&1)" && fail "jeton d'invitation dans les journaux" || ok "jeton jamais dans les journaux (nginx, Caddy, serveur)"
}

sql() { dc exec -T postgres psql -tAq -U anime -d anime -c "$1"; }

step_backup() {
    echo "== Sauvegarde vérifiée, rotation, restauration de bout en bout"
    dc exec -T backup sh /app-scripts/backup-loop.sh once > "$E2E/backup.log" 2>&1 || { cat "$E2E/backup.log"; fail "sauvegarde"; }
    st=$(cat "$DATA/backups/status.json")
    [ "$(json "d['ok'] and d['verified']" <<< "$st")" = True ] && ok "sauvegarde faite et vérifiée par une restauration d'essai" || fail "état : $st"
    dump=$(json "d['file']" <<< "$st")
    [ "$(stat -c '%a %u' "$DATA/backups/$dump")" = "600 0" ] && ok "fichier de sauvegarde lisible par root seulement" || fail "droits $(stat -c '%a %u' "$DATA/backups/$dump")"
    [ "$(sql "SELECT count(*) FROM pg_database WHERE datname LIKE 'anime_verify%'")" = 0 ] && ok "base d'essai supprimée" || fail "base d'essai restante"
    pw=$(docker run --rm -v anime-server_secrets_pg:/p:ro alpine:3 cat /p/db_password)
    grep -qF "$pw" "$E2E/backup.log" "$DATA/backups/status.json" && fail "mot de passe affiché" || ok "mot de passe de la base jamais affiché"
    # Changements après la sauvegarde, puis restauration : on doit retrouver l'état sauvegardé.
    sql "UPDATE app_user SET enabled = false WHERE username = 'alice'; INSERT INTO app_user (username, password_hash, role) VALUES ('bob', NULL, 'USER');" > /dev/null
    (cd "$DATA" && sh app/restore.sh "backups/$dump" --yes) > "$E2E/restore.log" 2>&1 || { tail -20 "$E2E/restore.log"; fail "restauration"; }
    [ "$(sql "SELECT count(*) FROM app_user WHERE username = 'bob'")" = 0 ] && [ "$(sql "SELECT enabled FROM app_user WHERE username = 'alice'")" = t ] \
        && ok "base restaurée : état de la sauvegarde retrouvé" || fail "contenu après restauration"
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d '{"login":"alice","password":"le-mot-de-passe-d-alice"}' "$PUB/api/auth/login")" = 200 ] && ok "alice se reconnecte après restauration" || fail "connexion"
    ls "$DATA/backups" | grep -q '^avant-restauration-' && ok "sauvegarde de sécurité faite avant la restauration" || fail "pas de sauvegarde de sécurité"
    # Rotation : 400 jours de sauvegardes fictives jusqu'à aujourd'hui (deux par jour certains jours), en plus de la vraie.
    today=$(date +%Y-%m-%d)
    for i in $(seq 0 399); do
        d=$(date -d "$today -$i days" +%Y%m%d)
        touch "$DATA/backups/anime-db-$d-030000.dump"
        [ $((i % 10)) = 0 ] && touch "$DATA/backups/anime-db-$d-150000.dump"
    done
    dc exec -T backup sh /app-scripts/backup-loop.sh rotate > /dev/null
    kept=$(ls "$DATA/backups" | grep -E '^anime-db-[0-9]{8}-' | sort -r)
    n=$(echo "$kept" | wc -l)
    newest7=$(echo "$kept" | head -7 | cut -c10-17 | tr '\n' ' ')
    expected7=$(for i in 0 1 2 3 4 5 6; do date -d "$today -$i days" +%Y%m%d; done | tr '\n' ' ')
    [ "$n" = 17 ] && [ "$newest7" = "$expected7" ] \
        && ok "rotation : 7 jours, puis semaines et mois ($n gardées sur 441)" || fail "rotation : $n gardées ; $newest7 (attendu $expected7)"
}

build_variant() { # build_variant <version> <migration en plus : oui|non> — version CASSÉE (santé toujours en échec)
    v=$1
    dir=$E2E/variant-$v
    rm -rf "$dir" && mkdir -p "$dir"
    if [ "$2" = oui ]; then
        cp -r "${CTX_DIR:-/tmp/claude-0/ctx}/pkg-backend" "$dir/pkg"
        echo "CREATE TABLE e2e_migration_v99 (id INT);" > "$dir/pkg/src/main/resources/db/migration/V99__e2e_test.sql"
        (cd "$dir/pkg" && mvn -o -q package -DskipTests > /dev/null 2>&1)
        mkdir -p "$dir/ctx/build/target" && cp -r "$dir/pkg/target/quarkus-app" "$dir/ctx/build/target/"
        (cd "$root" && docker build -q -f backend/Dockerfile --build-arg APP_VERSION="$v" --build-context build="$dir/ctx" \
            --build-context ffmpeg="${CTX_DIR:-/tmp/claude-0/ctx}/ffmpeg" -t "$REGISTRY/anime-server-backend:$v-base" . > /dev/null)
        base="$REGISTRY/anime-server-backend:$v-base"
    else
        base="$REGISTRY/anime-server-backend:$VERSION"
    fi
    printf 'FROM %s\nHEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=2 CMD false\n' "$base" > "$dir/Dockerfile"
    docker build -q -t "$REGISTRY/anime-server-backend:$v" "$dir" > /dev/null
    docker tag "$REGISTRY/anime-server-web:$VERSION" "$REGISTRY/anime-server-web:$v"
    docker push -q "$REGISTRY/anime-server-backend:$v" > /dev/null && docker push -q "$REGISTRY/anime-server-web:$v" > /dev/null
}

step_update() {
    echo "== Mise à jour : réussie, puis ratée (retour arrière), puis ratée après migration (restauration)"
    good=1.0.1
    docker tag "$REGISTRY/anime-server-backend:$VERSION" "$REGISTRY/anime-server-backend:$good"
    docker tag "$REGISTRY/anime-server-web:$VERSION" "$REGISTRY/anime-server-web:$good"
    docker push -q "$REGISTRY/anime-server-backend:$good" > /dev/null && docker push -q "$REGISTRY/anime-server-web:$good" > /dev/null
    users=$(sql "SELECT count(*) FROM app_user")
    (cd "$DATA" && sh app/update.sh $good --yes) > "$E2E/update1.log" 2>&1 || { cat "$E2E/update1.log"; fail "mise à jour $good"; }
    [ "$(grep '^ANIME_VERSION=' "$DATA/nas.env")" = "ANIME_VERSION=$good" ] && grep -q "OK : version $good en service" "$E2E/update1.log" \
        && ok "mise à jour $VERSION -> $good réussie" || fail "version après mise à jour"
    ls "$DATA/backups" | grep -q '^avant-maj-' && ok "sauvegarde faite avant la mise à jour" || fail "pas de sauvegarde avant-maj"
    [ "$(sql "SELECT count(*) FROM app_user")" = "$users" ] && ok "données intactes" || fail "données"

    build_variant 1.0.2 non
    if (cd "$DATA" && sh app/update.sh 1.0.2 --yes) > "$E2E/update2.log" 2>&1; then cat "$E2E/update2.log"; fail "version cassée acceptée"; fi
    grep -q "retour arrière terminé : version $good en service" "$E2E/update2.log" && [ "$(grep '^ANIME_VERSION=' "$DATA/nas.env")" = "ANIME_VERSION=$good" ] \
        && ok "version cassée : retour automatique à $good" || { cat "$E2E/update2.log"; fail "retour arrière"; }
    grep -q "restauration de" "$E2E/update2.log" && fail "restauration inutile" || ok "pas de migration : base laissée telle quelle"
    wait_backend; ok "serveur $good en bonne santé après le retour arrière"

    build_variant 1.0.3 oui
    if (cd "$DATA" && sh app/update.sh 1.0.3 --yes) > "$E2E/update3.log" 2>&1; then cat "$E2E/update3.log"; fail "version cassée acceptée"; fi
    grep -q "la base a été modifiée" "$E2E/update3.log" && ok "migration détectée : sauvegarde d'avant mise à jour restaurée" || { cat "$E2E/update3.log"; fail "restauration après migration"; }
    [ "$(sql "SELECT count(*) FROM pg_tables WHERE tablename = 'e2e_migration_v99'")" = 0 ] && [ "$(sql "SELECT count(*) FROM app_user")" = "$users" ] \
        && ok "base revenue à l'état d'avant (migration annulée, données intactes)" || fail "état de la base"
    wait_backend
    [ "$(pub -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" \
        -d '{"login":"alice","password":"le-mot-de-passe-d-alice"}' "$PUB/api/auth/login")" = 200 ] && ok "le site répond après le retour arrière" || fail "site"
}

steps=${*:-"first_start secrets_lost wizard invitation backup update"}
setup
for s in $steps; do "step_$s"; done
[ "${KEEP:-0}" = 1 ] || clean
echo "== $pass vérifications réussies"
