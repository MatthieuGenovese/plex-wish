#!/bin/sh
# Génère /etc/nginx/real-ip.conf à partir de TRUSTED_PROXY_IPS (ARCHITECTURE §5.4.1).
# Une ligne set_real_ip_from par adresse ou plage (séparées par des virgules ou des espaces).
# Vide = aucune confiance : X-Forwarded-For est toujours ignoré.
set -eu
out=/etc/nginx/real-ip.conf
: > "$out"
for entry in $(echo "${TRUSTED_PROXY_IPS:-}" | tr ',' ' '); do
    case "$entry" in
        0.0.0.0/0|::/0)
            echo "15-real-ip.sh: TRUSTED_PROXY_IPS=$entry ferait confiance à tout le monde : refusé" >&2
            exit 1 ;;
    esac
    if ! echo "$entry" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}(/[0-9]{1,2})?$|^[0-9A-Fa-f:]+(/[0-9]{1,3})?$'; then
        echo "15-real-ip.sh: entrée invalide dans TRUSTED_PROXY_IPS : '$entry'" >&2
        exit 1
    fi
    echo "set_real_ip_from $entry;" >> "$out"
done
if [ -s "$out" ]; then
    echo "15-real-ip.sh: X-Forwarded-For accepté uniquement depuis : $(tr '\n' ' ' < "$out")"
else
    echo "15-real-ip.sh: TRUSTED_PROXY_IPS vide, X-Forwarded-For toujours ignoré"
fi
