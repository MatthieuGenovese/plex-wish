#!/bin/sh
# Analyse d'une image exportée (docker save), lancée par check-image-secrets.sh dans un conteneur jetable
# (image Maven/JDK : GNU grep pour la vitesse, jar pour ouvrir les archives de l'application).
#   sh image-scan.sh <image.tar> <valeurs-connues> <nom-affiché>
set -eu
tar=$1
known=$2
name=$3
work=/tmp/scan
rm -rf "$work"; mkdir -p "$work/img" "$work/fs"
tar -xf "$tar" -C "$work/img"

# Chaque fichier de l'archive : couche (tar, éventuellement gzip) ou métadonnées (JSON : config, ENV, LABEL).
n=0
find "$work/img" -type f > "$work/list"
while IFS= read -r f; do
    n=$((n + 1))
    magic=$(head -c 4 "$f" | od -An -tx1 | tr -d ' \n')
    case "$magic" in
        1f8b*) mkdir -p "$work/fs/$n"; zcat "$f" | tar -x -C "$work/fs/$n" 2>/dev/null || true ;;
        28b52ffd) echo "ÉCHEC : couche compressée en zstd, non analysable ici : $f" >&2; exit 1 ;;
        *) if tar -tf "$f" > /dev/null 2>&1; then mkdir -p "$work/fs/$n"; tar -xf "$f" -C "$work/fs/$n" 2>/dev/null || true
           else mkdir -p "$work/meta"; cp "$f" "$work/meta/$n.json"; fi ;;
    esac
done < "$work/list"

# Les jar de l'application (pas ceux des bibliothèques) : application.properties et ressources compressées dedans.
find "$work/fs" -type f -name '*.jar' \( -path '*/app/app/*' -o -name 'quarkus-run.jar' -o -path '*/app/quarkus/*' \) > "$work/jars.list"
while IFS= read -r j; do
    d="$work/jars/$(echo "$j" | md5sum | cut -c1-12)"
    mkdir -p "$d"
    (cd "$d" && jar xf "$j") 2>/dev/null || true
done < "$work/jars.list"

findings="$work/findings"
: > "$findings"
rel() { sed "s#^$work/fs/[0-9]*/##; s#^$work/##"; }

echo "Analyse de $name : $(find "$work/fs" -type f | wc -l) fichiers dans $(ls "$work/fs" | wc -l) couches"

# 1. Noms de fichiers sensibles.
find "$work/fs" "$work/jars" -type f \( -name '.env' -o -name '.env.*' -o -name 'nas.env' -o -name '*.jks' \
        -o -name '*.keystore' -o -name 'keystore.properties' -o -name 'local.properties' -o -name 'id_rsa*' \
        -o -name 'id_ed25519*' -o -name 'id_ecdsa*' -o -name '*.p12' -o -name '*.pfx' -o -path '*/docs/private/*' \) \
        ! -name '.env.example' 2>/dev/null | rel | sed 's/^/fichier sensible : /' >> "$findings"

# 2. Motifs de secrets (fichiers texte ; -I ignore les binaires).
pattern='-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{40,}|eyJhbGciOiJIUzI1NiJ9\.eyJhdWQiOi|AKIA[0-9A-Z]{16}'
{ grep -rIlE -e "$pattern" "$work/fs" "$work/jars" "$work/meta" 2>/dev/null || true; } | rel \
    | sed 's/^/motif de secret dans : /' >> "$findings"

# 3. Valeurs connues (une par ligne), recherchées telles quelles, y compris dans les binaires.
if [ -s "$known" ]; then
    { grep -rlF -f "$known" "$work/fs" "$work/jars" "$work/meta" 2>/dev/null || true; } | rel \
        | sed 's/^/valeur secrète connue dans : /' >> "$findings"
fi

if [ -s "$findings" ]; then
    sed 's/^/  - /' "$findings"
    echo "ÉCHEC : $(wc -l < "$findings") problème(s) dans $name. Image NON publiable." >&2
    exit 1
fi
echo "OK : aucun secret trouvé dans $name"
