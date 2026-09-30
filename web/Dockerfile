# --- Build : Node ------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /build
ENV NG_CLI_ANALYTICS=false

# Dépendances d'abord : couche en cache tant que package*.json ne change pas.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# --- Exécution : nginx ----------------------------------------------------------------------
FROM nginx:alpine
# Adresses (ou plages CIDR) autorisées à fournir X-Forwarded-For, séparées par des virgules.
# Vide par défaut : aucune confiance. docker-compose.yml fournit la valeur réelle.
ENV TRUSTED_PROXY_IPS=""
COPY --chmod=755 nginx/15-real-ip.sh /docker-entrypoint.d/15-real-ip.sh
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /build/dist/web/browser/ /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
