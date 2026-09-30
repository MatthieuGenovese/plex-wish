#!/bin/sh
# Exécuté DANS un conteneur Alpine (voir generate-fake-library.sh / .ps1) :
#   /sample.txt = library-sample.txt (lecture seule), /media = volume ou dossier cible.
# Crée un fichier vide par ligne. Linux accepte tous les noms de la liste (":", "?", "\"", "⁄"…),
# que Windows refuserait : c'est pour ça que tout se passe dans Docker.
set -eu
cd /media
# Repartir d'une arborescence propre.
find /media -mindepth 1 -delete
# Dossiers (chaque ligne sans son dernier élément), puis fichiers. Noms passés séparés par des NUL :
# espaces, apostrophes et guillemets passent tels quels.
sed 's#/[^/]*$##' /sample.txt | sort -u | tr '\n' '\0' | xargs -0 mkdir -p --
tr '\n' '\0' < /sample.txt | xargs -0 touch --
echo "Bibliothèque factice : $(find /media -type f | wc -l) fichiers vides, $(find /media -mindepth 1 -maxdepth 1 -type d | wc -l) dossiers d'animés"
