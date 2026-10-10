#!/bin/sh
# ESSAIS SEULEMENT (cet environnement n'a pas d'accès direct à Maven Central ni à npm dans « docker build ») :
# construit les images avec le VRAI Dockerfile, mais en remplaçant les étapes de compilation par des sorties
# compilées sur la machine (jar Quarkus, dist Angular, ffmpeg et libx264 extraits d'une image construite avec la vraie étape « ffmpeg » : bin/ et usr/lib/x86_64-linux-gnu/ dans $ctx/ffmpeg), puis les pousse sur un
# registre local. Sur le PC de Matthieu, publish-images.sh construit tout dans Docker.
#
#   scripts/test/build-test-images.sh <version> [registre]      (défaut : localhost:5000/test)
set -eu
root=$(cd "$(dirname "$0")/../.." && pwd)
version=$1
registry=${2:-localhost:5000/test}
ctx=${CTX_DIR:-/tmp/claude-0/ctx}
cd "$root"
[ -d "$ctx/build/build/target/quarkus-app" ] || { echo "jar absent : $ctx/build/build/target/quarkus-app" >&2; exit 1; }
docker build -q -f backend/Dockerfile --build-arg APP_VERSION="$version" --build-arg GIT_COMMIT="$(git rev-parse --short=12 HEAD)" \
    --build-context build="$ctx/build" --build-context ffmpeg="$ctx/ffmpeg" \
    -t "$registry/anime-server-backend:$version" . > /dev/null
docker build -q --build-arg APP_VERSION="$version" --build-context build="$ctx/webb" \
    -t "$registry/anime-server-web:$version" web > /dev/null
docker push -q "$registry/anime-server-backend:$version" > /dev/null
docker push -q "$registry/anime-server-web:$version" > /dev/null
echo "images $version poussées sur $registry"
