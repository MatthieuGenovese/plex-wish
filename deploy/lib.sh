# Fonctions communes aux scripts du NAS (install, update, restore, repair). Chargé par « . ./app/lib.sh »
# depuis le dossier du projet. Aucun secret n'est jamais affiché.
PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }
die() { log "ÉCHEC : $*" >&2; exit 1; }

# docker compose (plugin v2, Container Manager récent) ou docker-compose (binaire historique).
if docker compose version > /dev/null 2>&1; then
    compose() { docker compose --env-file nas.env -f compose.yml "$@"; }
elif command -v docker-compose > /dev/null 2>&1; then
    compose() { docker-compose --env-file nas.env -f compose.yml "$@"; }
else
    die "ni « docker compose » ni « docker-compose » : Container Manager est-il installé ?"
fi

# wait_healthy <service> <secondes> : attend l'état « healthy » du conteneur (healthcheck Docker).
wait_healthy() {
    _id=$(compose ps -q "$1" 2>/dev/null)
    _left=$2
    while [ "$_left" -gt 0 ]; do
        [ -n "$_id" ] || _id=$(compose ps -q "$1" 2>/dev/null)
        if [ -n "$_id" ]; then
            _st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$_id" 2>/dev/null || echo "?")
            [ "$_st" = "healthy" ] && return 0
            [ "$_st" = "exited" ] && return 1
        fi
        sleep 3
        _left=$((_left - 3))
    done
    return 1
}
