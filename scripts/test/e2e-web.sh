#!/bin/bash
# ESSAI DE BOUT EN BOUT DU LECTEUR WEB (phase 10) : vraie pile (images du registre local, nginx avec sa CSP, Caddy,
# serveur avec ffmpeg), fichiers vidéo générés, puis les essais Playwright de web/e2e dans Google Chrome.
# Tout est créé dans un dossier jetable et supprimé à la fin (KEEP=1 pour garder la pile).
#
#   scripts/test/build-test-images.sh 1.9.3      (après scripts/test/refresh-test-builds.sh)
#   VERSION=1.9.3 scripts/test/e2e-web.sh
#
# Prérequis sur la machine : docker, ffmpeg avec libx264 (pour fabriquer les fichiers), Node, Google Chrome
# (CHROME_PATH, défaut /opt/google/chrome/chrome ; le Chromium de Playwright ne décode ni H.264 ni AAC).
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
E2E=${E2E_DIR:-/tmp/claude-0/e2e-web}
DATA=$E2E/data
MEDIA=$E2E/media
REGISTRY=${REGISTRY:-localhost:5000/test}
VERSION=${VERSION:-1.9.3}
LAN_PORT=18081
PUBLIC_PORT=18444
DOMAIN=anime.e2e.test
LAN="http://localhost:$LAN_PORT"
PUB="https://$DOMAIN:$PUBLIC_PORT"
pub() { curl -sk --noproxy "*" --resolve "$DOMAIN:$PUBLIC_PORT:127.0.0.1" "$@"; }
lan() { curl -s --noproxy "*" "$@"; }
json() { python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"; }
dc() { (cd "$DATA" && docker compose --env-file nas.env -f compose.yml "$@"); }
say() { echo "==> $*"; }

clean() {
    (cd "$DATA" 2>/dev/null && docker compose --env-file nas.env -f compose.yml down -v --remove-orphans > /dev/null 2>&1) || true
    docker volume rm -f anime-server_pgdata anime-server_secrets_app anime-server_secrets_pg > /dev/null 2>&1 || true
    rm -rf "$E2E"
}

ff() { ffmpeg -nostdin -hide_banner -v error -y "$@"; }

media() {
    say "Fichiers de test (générés)"
    mkdir -p "$MEDIA/Frieren/Saison 1" "$MEDIA/Air Gear"
    local font="$root/android/app/src/main/res/font/figtree_extrabold.ttf" # Figtree (SIL OFL 1.1)
    cat > "$E2E/subs.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Dialogue,Figtree ExtraBold,28,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1
Style: Panneau,Figtree ExtraBold,22,&H0000E0FF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,8,20,20,20,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.20,0:00:20.00,Dialogue,,0,0,0,,Bonjour, {\i1}voyageuse{\i0} !
Dialogue: 0,0:00:00.20,0:00:20.00,Panneau,,0,0,0,,{\pos(320,40)}Panneau en haut
ASS
    ff -f lavfi -i "testsrc2=size=640x360:rate=24:duration=90" -f lavfi -i "sine=f=440:duration=90" -f lavfi -i "sine=f=660:duration=90" \
        -i "$E2E/subs.ass" -map 0 -map 1 -map 2 -map 3 -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -c:s ass \
        -metadata:s:a:0 language=jpn -metadata:s:a:1 language=fre -metadata:s:a:1 title=VF \
        -metadata:s:s:0 language=fre -metadata:s:s:0 title=Dialogues \
        -attach "$font" -metadata:s:t:0 mimetype=application/x-truetype-font -metadata:s:t:0 filename=figtree.ttf \
        "$MEDIA/Frieren/Saison 1/Frieren - S01E01.mkv"
    ff -f lavfi -i "testsrc2=size=640x360:rate=24:duration=12" -f lavfi -i "sine=f=330:duration=12" -i "$E2E/subs.ass" \
        -map 0 -map 1 -map 2 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -c:s mov_text -metadata:s:s:0 language=fre \
        -movflags +faststart "$MEDIA/Frieren/Saison 1/Frieren - S01E02.mp4"
    ff -f lavfi -i "testsrc2=size=640x360:rate=25:duration=8" -f lavfi -i "sine=f=550:duration=8" -c:v mpeg4 -vtag XVID \
        -c:a mp2 "$MEDIA/Air Gear/Air Gear - S01E01.avi"
    chown -R 1026:100 "$MEDIA" && chmod -R u=rwX,g=rX,o= "$MEDIA"
}

stack() {
    say "Installation (version $VERSION)"
    docker run --rm --entrypoint cat "$REGISTRY/anime-server-backend:$VERSION" /app/deploy/install.sh > "$E2E/anime-install.sh"
    sh "$E2E/anime-install.sh" --version "$VERSION" --registry "$REGISTRY" --media "$MEDIA" --domain "$DOMAIN" --data "$DATA" \
        --lan-port $LAN_PORT --public-port $PUBLIC_PORT > "$E2E/install.log" 2>&1 || { tail -30 "$E2E/install.log"; exit 1; }
    grep -q "^WEB_CACHE_PATH=$DATA/web-cache$" "$DATA/nas.env" || { echo "WEB_CACHE_PATH absent de nas.env" >&2; exit 1; }
    # Essai : certificat interne de Caddy au lieu de Let's Encrypt (pas de vrai nom de domaine ici).
    sed 's/issuer acme {/issuer internal {/; /disable_http_challenge/d' "$DATA/app/Caddyfile" > "$DATA/Caddyfile.e2e"
    echo "CADDYFILE=./Caddyfile.e2e" >> "$DATA/nas.env"
    dc up -d caddy > /dev/null 2>&1
    say "Assistant (compte « chef »), fin de l'installation, scan"
    token=$(lan -H 'Content-Type: application/json' -d '{"username":"chef","password":"mot-de-passe-du-chef"}' "$LAN/api/setup/admin" | json "d['accessToken']")
    lan -o /dev/null -X POST -H "Authorization: Bearer $token" "$LAN/api/setup/finish"
    for i in $(seq 1 30); do [ "$(pub -o /dev/null -w '%{http_code}' "$PUB/")" = 200 ] && break; sleep 2; done
    token=$(pub -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" -d '{"login":"chef","password":"mot-de-passe-du-chef"}' \
        "$PUB/api/auth/login" | json "d['accessToken']")
    pub -o /dev/null -X POST -H "Authorization: Bearer $token" -H "Origin: https://$DOMAIN" "$PUB/api/admin/library/scan"
    for i in $(seq 1 30); do
        n=$(pub -H "Authorization: Bearer $token" "$PUB/api/anime" | json "d['total']")
        [ "$n" = 2 ] && break; sleep 2
    done
    [ "$n" = 2 ] || { echo "scan : $n animé(s)" >&2; exit 1; }
    # CSP servie (S1, S2 acceptés) : rien d'autre.
    pub -sI "$PUB/" | grep -i '^content-security-policy' | tee "$E2E/csp.txt"
}

run_tests() {
    say "Essais Playwright (Google Chrome)"
    (cd "$root/web" && CHROME_PATH=${CHROME_PATH:-/opt/google/chrome/chrome} E2E_DOMAIN="$DOMAIN" E2E_PORT=$PUBLIC_PORT \
        PATH="${NODE_BIN:+$NODE_BIN:}$PATH" npx playwright test)
}

trap '[ "${KEEP:-0}" = 1 ] || clean' EXIT
clean
mkdir -p "$E2E"
media
stack
run_tests
say "OK"
