# Anime Server

Serveur de streaming d'animés auto-hébergé, pour un petit groupe privé, sur un NAS Synology (Docker).
Backend Quarkus + PostgreSQL, interface web Angular servie par nginx.

- Architecture et décisions : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Avancement : [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Spike vidéo (phase 0) : [`docs/SPIKE.md`](docs/SPIKE.md)

> État : **phase 1 (socle)**. L'authentification, la bibliothèque et les vraies pages arrivent en phases 2 à 4.

## Prérequis

| Pour… | Il faut |
|---|---|
| Lancer le tout (NAS ou PC) | Docker + Docker Compose (Container Manager sur Synology, Docker Desktop sous Windows) |
| Développer le backend | JDK 21 **et Docker Desktop lancé** (la base de dev et de test est démarrée automatiquement dans Docker). Maven n'est pas nécessaire : utiliser `mvnw` |
| Développer le front | Node.js 24 LTS (ou 22.22.3+) |

## Lancer le backend (dev)

```powershell
cd backend
.\mvnw.cmd quarkus:dev          # cmd : mvnw.cmd quarkus:dev   ·   Linux/macOS : ./mvnw quarkus:dev
```

- API sur <http://localhost:8080>, Swagger UI sur <http://localhost:8080/q/swagger-ui>.
- PostgreSQL : pas d'installation, Quarkus **Dev Services** démarre un conteneur `postgres:16-alpine` jetable et applique les migrations Flyway.
- Tests : `.\mvnw.cmd test` (Docker Desktop doit tourner).

## Lancer le front (dev)

```powershell
cd web
npm ci
npm start                        # http://localhost:4200
```

`/api` est redirigé vers `localhost:8080` (`proxy.conf.json`) : lancer le backend avant. Tests : `npm test`.

## Lancer le tout avec Docker

```powershell
copy .env.example .env           # Linux : cp .env.example .env
# éditer .env : au minimum POSTGRES_PASSWORD et MEDIA_PATH
docker compose up -d --build
```

Puis ouvrir <http://localhost:8080> (ou `WEB_PORT`). La page d'accueil doit afficher « API joignable ».

- Trois conteneurs : `postgres` (non exposé), `backend` (non exposé), `web` (nginx, seul port publié).
- Arrêt : `docker compose down`. Les données restent dans le volume `pgdata` (`down -v` les efface).
- Mise à jour du code : `docker compose up -d --build`.

### Sur le Synology (Container Manager)

1. Copier le dépôt sur le NAS (ex. `/volume1/docker/anime-server`) et créer `.env` à partir de `.env.example`.
2. Container Manager → **Projet** → **Créer** → chemin du dossier → il détecte `docker-compose.yml`.
3. Reverse proxy DSM (*Panneau de configuration → Portail de connexion → Avancé → Proxy inversé*) : `https://anime.mondomaine` → `http://localhost:<WEB_PORT>`. Le HTTPS se termine au DSM.

## Dossier média

- `MEDIA_PATH` (dans `.env`) = dossier des vidéos **sur l'hôte**, par ex. `/volume1/animes` sur le NAS, `./dev-media` en local.
- Il est monté **en lecture seule** dans le backend (`/media`). L'application ne modifie, ne déplace et ne supprime jamais un fichier.
- Droits : le backend tourne avec l'utilisateur `PUID:PGID` (`.env`). Il doit avoir le droit de **lire** le dossier. Sur Synology, trouver ces numéros en SSH avec `id <utilisateur>` (un utilisateur DSM qui a accès en lecture au dossier partagé).
- `dev-media/` sert aux tests locaux : son contenu n'est jamais versionné.

## Administrateur initial *(phase 2)*

Renseigner `INITIAL_ADMIN_USERNAME` et `INITIAL_ADMIN_PASSWORD` dans `.env` avant le premier lancement : le compte est créé au démarrage s'il n'existe aucun admin. Les deux variables peuvent ensuite être retirées. `JWT_SECRET` et `STREAM_SIGNING_SECRET` devront aussi être définis (deux secrets différents, 32 caractères minimum).

## Scan de la bibliothèque *(phase 3)*

Depuis la page Admin (ou `POST /api/admin/library/scan`), puis consultation du rapport des fichiers ignorés.

## Swagger / OpenAPI

- En dev : <http://localhost:8080/q/swagger-ui>, toujours actif.
- Avec Docker : mettre `SWAGGER_ENABLED=true` dans `.env`, relancer, puis `http://<hôte>:<WEB_PORT>/q/swagger-ui`. À remettre à `false` ensuite.
