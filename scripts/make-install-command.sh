#!/bin/sh
# Même chose que make-install-command.ps1, pour Linux ou macOS (D1.7).
#   scripts/make-install-command.sh <compte-images> <dossier-média> <domaine> [--funnel]
set -eu
[ $# -ge 3 ] || { echo "usage : $0 <compte-images> <dossier-média> <domaine> [--funnel]" >&2; exit 2; }
owner=$1; media=$2; domain=$3; extra=""
[ "${4:-}" = "--funnel" ] && extra=" --funnel"
version=$(tr -d ' \r\n' < "$(dirname "$0")/../VERSION")
registry="ghcr.io/$(echo "$owner" | tr 'A-Z' 'a-z')"
printf "Jeton GitHub en lecture (read:packages) : "
stty -echo 2> /dev/null || true; read -r token; stty echo 2> /dev/null || true; echo
echo "$token" | grep -Eq '^(ghp_|github_pat_)[A-Za-z0-9_]+$' || { echo "Ce n'est pas un jeton GitHub." >&2; exit 1; }
cat <<CMD

=== Commande d'installation (Planificateur de tâches, utilisateur root) ===
set -e
export PATH=/usr/local/bin:\$PATH
echo '$token' | docker login ghcr.io -u $owner --password-stdin
docker run --rm --entrypoint cat $registry/anime-server-backend:$version /app/deploy/install.sh > /tmp/anime-install.sh
sh /tmp/anime-install.sh --version $version --registry $registry --media '$media' --domain $domain$extra

Pour une SIMULATION d'abord : ajouter --dry-run à la fin de la dernière ligne.
CMD
