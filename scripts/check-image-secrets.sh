#!/bin/sh
# Vérifie qu'une image Docker ne contient aucun secret, AVANT toute publication (D1.1).
#
#   scripts/check-image-secrets.sh <image> [fichier-de-valeurs-connues]
#
# Toutes les couches sont inspectées (un fichier ajouté puis supprimé reste dans une couche inférieure),
# ainsi que la configuration de l'image (ENV, LABEL) et le contenu des jar de l'application. Refus si :
#   - un fichier sensible par son nom (.env, nas.env, keystore, local.properties, clés SSH, .p12, docs/private) ;
#   - un motif de secret (clé privée PEM, jeton GitHub, jeton TMDB, clé AWS) ;
#   - une des valeurs connues (secrets de ton .env, de local.properties, de keystore.properties), si fournies.
# Les valeurs trouvées ne sont JAMAIS affichées : seulement le fichier qui les contient.
# L'analyse tourne dans un conteneur jetable (fonctionne pareil sous Windows et Linux), avec l'image Maven déjà
# téléchargée pour construire le serveur.
set -eu

[ $# -ge 1 ] || { echo "usage : $0 <image> [valeurs-connues]" >&2; exit 2; }
image=$1
known=${2:-}
here=$(cd "$(dirname "$0")" && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
chmod 700 "$work"
docker save "$image" -o "$work/image.tar"
if [ -n "$known" ] && [ -s "$known" ]; then cp "$known" "$work/known"; else : > "$work/known"; fi
chmod 600 "$work/known"

docker run --rm --network none -v "$work:/scan:ro" -v "$here/image-scan.sh:/image-scan.sh:ro" \
    --entrypoint sh "${SCAN_IMAGE:-maven:3.9-eclipse-temurin-21}" /image-scan.sh /scan/image.tar /scan/known "$image"
