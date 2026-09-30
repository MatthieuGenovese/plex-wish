#!/bin/sh
# Génère la bibliothèque factice (≈ 33 000 fichiers vides) à partir de library-sample.txt,
# dans un volume Docker (par défaut) ou un dossier ignoré par git.
#
#   scripts/generate-fake-library.sh                   → volume Docker "anime-fake-media"
#   scripts/generate-fake-library.sh "$PWD/fake-media" → dossier fake-media/ (Linux uniquement)
#
# Puis : docker compose -f docker-compose.yml -f docker-compose.fake-media.yml up -d --build
# (voir README, « Tester le scan complet »).
set -eu
cd "$(dirname "$0")/.."
TARGET="${1:-anime-fake-media}"
case "$TARGET" in
    */*) mkdir -p "$TARGET" ;;
    *) docker volume create "$TARGET" > /dev/null ;;
esac
docker run --rm \
    -v "$TARGET:/media" \
    -v "$PWD/backend/src/test/resources/library-sample.txt:/sample.txt:ro" \
    -v "$PWD/scripts/fake-library:/scripts:ro" \
    alpine:3 sh /scripts/generate.sh
