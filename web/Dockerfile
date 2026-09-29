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
# IP (ou plage CIDR) du reverse proxy autorisée à fournir X-Forwarded-For.
ENV TRUSTED_PROXY_IPS=172.16.0.0/12
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /build/dist/web/browser/ /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
