#!/bin/sh
# ESSAIS : recompile le jar du serveur et le web sur la machine, pour build-test-images.sh.
set -eu
root=$(cd "$(dirname "$0")/../.." && pwd)
ctx=${CTX_DIR:-/tmp/claude-0/ctx}
# Copie du serveur ailleurs : ne pas toucher au target/ d'une suite de tests qui tourne peut-être.
pkg="$ctx/pkg-backend"
rm -rf "$pkg" && mkdir -p "$pkg" && cp -r "$root/backend/pom.xml" "$root/backend/src" "$pkg/"
(cd "$pkg" && mvn -o -q package -DskipTests)
rm -rf "$ctx/build/build/target/quarkus-app" && mkdir -p "$ctx/build/build/target" && cp -r "$pkg/target/quarkus-app" "$ctx/build/build/target/"
if [ "${WEB:-1}" = 1 ]; then
    (cd "$root/web" && PATH="${NODE_BIN:+$NODE_BIN:}$PATH" npm run build > /dev/null)
    rm -rf "$ctx/webb/build/dist/web/browser" && mkdir -p "$ctx/webb/build/dist/web" && cp -r "$root/web/dist/web/browser" "$ctx/webb/build/dist/web/"
fi
echo "compilations à jour dans $ctx"
