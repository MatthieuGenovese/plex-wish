#!/bin/sh
# Vérifie la chaîne d'IP reverse proxy → nginx → backend (ARCHITECTURE §5.4.1).
# À lancer sur la machine qui fait tourner docker compose (le NAS, ou le PC en local),
# depuis la racine du dépôt, stack démarrée.
#
#   ADMIN_USER=admin ADMIN_PASSWORD='...' scripts/check-client-ip.sh
#
# Trois vérifications :
#  1. « client direct » : une requête qui arrive sur nginx depuis une IP NON listée dans
#     TRUSTED_PROXY_IPS avec un X-Forwarded-For forgé → le backend doit l'ignorer
#     (depuis nginx lui-même, puis depuis un autre conteneur du réseau Docker) ;
#  2. « derrière le proxy de confiance » : une requête qui arrive depuis l'hôte (comme le
#     reverse proxy DSM) avec X-Forwarded-For → le backend doit lire l'IP ajoutée par le proxy.
set -eu

BASE_URL="${BASE_URL:-http://localhost:${WEB_PORT:-8080}}"
: "${ADMIN_USER:?définir ADMIN_USER}"
: "${ADMIN_PASSWORD:?définir ADMIN_PASSWORD}"
FORGED=6.6.6.6
REAL=203.0.113.9
fail=0

# Jeton admin (mot de passe passé par l'entrée standard, pas dans la ligne de commande).
token=$(printf '{"login":"%s","password":"%s"}' "$ADMIN_USER" "$ADMIN_PASSWORD" |
    curl -fsS -H 'Content-Type: application/json' --data-binary @- "$BASE_URL/api/auth/login" |
    sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "Connexion admin impossible"; exit 1; }

# 1. Client direct : depuis l'intérieur du conteneur nginx (IP 127.0.0.1, jamais de confiance).
seen=$(docker compose exec -T web wget -qO- \
    --header "Authorization: Bearer $token" --header "X-Forwarded-For: $FORGED" \
    http://127.0.0.1/api/admin/debug/client-ip | sed -n 's/.*"ip":"\([^"]*\)".*/\1/p')
if [ "$seen" = "$FORGED" ]; then
    echo "ÉCHEC client direct : le X-Forwarded-For forgé ($FORGED) a été cru"; fail=1
else
    echo "OK   client direct : X-Forwarded-For forgé ignoré, IP vue = $seen"
fi

# 1b. Autre conteneur du réseau Docker (IP privée 172.x, mais pas la passerelle) : avec l'ancienne
#     confiance à tout 172.16.0.0/12, ce X-Forwarded-For forgé aurait été cru.
seen=$(docker compose exec -T backend curl -fsS \
    -H "Authorization: Bearer $token" -H "X-Forwarded-For: $FORGED" \
    http://web/api/admin/debug/client-ip | sed -n 's/.*"ip":"\([^"]*\)".*/\1/p')
if [ "$seen" = "$FORGED" ]; then
    echo "ÉCHEC autre conteneur : le X-Forwarded-For forgé ($FORGED) a été cru"; fail=1
else
    echo "OK   autre conteneur du réseau Docker : X-Forwarded-For forgé ignoré, IP vue = $seen"
fi

# 2. Derrière le proxy de confiance : depuis l'hôte, comme le reverse proxy DSM, qui AJOUTE
#    l'IP du client à un éventuel X-Forwarded-For déjà présent ("$FORGED, $REAL").
seen=$(curl -fsS -H "Authorization: Bearer $token" -H "X-Forwarded-For: $FORGED, $REAL" \
    "$BASE_URL/api/admin/debug/client-ip" | sed -n 's/.*"ip":"\([^"]*\)".*/\1/p')
if [ "$seen" = "$REAL" ]; then
    echo "OK   proxy de confiance : IP du client lue = $seen (valeur forgée $FORGED ignorée)"
else
    echo "ÉCHEC proxy de confiance : IP vue = $seen, attendu $REAL."
    echo "     L'hôte n'arrive pas par une adresse de TRUSTED_PROXY_IPS : vérifier cette variable"
    echo "     (passerelle du réseau Docker : docker network inspect <projet>_default)."
    fail=1
fi

echo "À faire aussi une fois sur le NAS : se connecter depuis un téléphone en 4G via https://<domaine>,"
echo "puis 'docker compose logs backend | grep Connexion' : l'IP affichée doit être celle du téléphone."
exit $fail
