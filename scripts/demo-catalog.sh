#!/bin/sh
# Catalogue de démonstration (outil de développement, phase Polish ; voir docs/DESIGN.md §6).
# Stack séparée « plexwish-demo » (sa propre base), jamais la stack normale ni le NAS.
#
#   scripts/demo-catalog.sh        → génère le catalogue, démarre la stack de démonstration, charge les données
#   scripts/demo-catalog.sh down   → arrête la stack de démonstration et efface sa base
#
# Prérequis : Docker, un .env (copié de .env.example). Interface : http://localhost:${DEMO_WEB_PORT:-8090}
# (compte : INITIAL_ADMIN_USERNAME / INITIAL_ADMIN_PASSWORD du .env).
set -eu
cd "$(dirname "$0")/.."
export WEB_PORT="${DEMO_WEB_PORT:-8090}"
# Réseau distinct de la stack normale (les deux peuvent tourner en même temps).
export DOCKER_SUBNET="${DEMO_DOCKER_SUBNET:-172.30.65.0/24}"
COMPOSE="docker compose -p plexwish-demo -f docker-compose.yml -f docker-compose.demo.yml"

if [ "${1:-}" = "down" ]; then
    $COMPOSE down -v
    exit 0
fi

# 1. Génération (Python de la bibliothèque standard, dans un conteneur : rien à installer).
docker run --rm -v "$PWD/scripts/demo-catalog:/tool:ro" -v "$PWD/demo-catalog:/out" \
    python:3.13-alpine python -I /tool/generate.py /out

# 2. Stack de démonstration (le backend crée le schéma et le compte admin au démarrage).
$COMPOSE up -d --build
echo "Attente du backend…"
i=0
until $COMPOSE exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1 FROM app_user LIMIT 1"' \
        2>/dev/null | grep -q 1; do
    i=$((i + 1)); [ "$i" -gt 90 ] && { echo "Le backend ne répond pas : $COMPOSE logs backend"; exit 1; }
    sleep 2
done

# 3. Chargement (refusé si la base contient de vrais fichiers).
$COMPOSE exec -T postgres sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < demo-catalog/demo.sql
echo "Catalogue de démonstration prêt : http://localhost:$WEB_PORT"
