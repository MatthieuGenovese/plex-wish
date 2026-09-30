# Anime Server

Serveur de streaming d'animés auto-hébergé, pour un petit groupe privé, sur un NAS Synology (Docker).
Backend Quarkus + PostgreSQL, interface web Angular servie par nginx.

- Architecture et décisions : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Avancement : [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Spike vidéo (phase 0) : [`docs/SPIKE.md`](docs/SPIKE.md)

> État : **phase 3 (bibliothèque)**. Les vraies pages arrivent en phase 4 : pour l'instant, l'API (connexion, scan, rapport, lecture de la bibliothèque) s'utilise via Swagger ou `curl`.

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
- En dev, un admin est créé automatiquement : `admin` / `admin-dev-password` (secrets et `PUBLIC_URL=http://localhost:4200` ont aussi des valeurs de dev ; ils ne servent jamais en prod).
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
# éditer .env : au minimum MEDIA_PATH, POSTGRES_PASSWORD, PUBLIC_URL, JWT_SECRET,
# STREAM_SIGNING_SECRET et INITIAL_ADMIN_PASSWORD (voir les commentaires du fichier)
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

## Administrateur initial

Renseigner `INITIAL_ADMIN_USERNAME` et `INITIAL_ADMIN_PASSWORD` (10 caractères minimum) dans `.env` avant le premier lancement : le compte est créé au démarrage **s'il n'existe aucun admin**. Ensuite les deux variables sont ignorées et peuvent être retirées. L'admin crée les autres comptes (`POST /api/admin/users`) ; il n'y a pas d'inscription publique.

## Sécurité : ce qu'il faut régler

Le backend **refuse de démarrer** en prod si `JWT_SECRET` ou `STREAM_SIGNING_SECRET` manquent, font moins de 32 caractères ou sont identiques, ou si `PUBLIC_URL` est absent ou invalide. Le message d'erreur (`docker compose logs backend`) dit quoi corriger.

### `PUBLIC_URL`

C'est l'adresse **exacte** que les utilisateurs tapent dans leur navigateur : schéma + domaine (+ port s'il n'est pas standard), sans chemin. Exemples : `https://anime.mondomaine.fr`, ou `http://localhost:8080` pour un essai local.

Le backend s'en sert pour refuser les requêtes d'écriture (login, refresh, logout, admin) envoyées par un **autre site** (en-tête `Origin`), et l'autorise dans sa configuration CORS. Si elle est **mal réglée** :

| Erreur | Ce qui casse |
|---|---|
| Absente ou invalide | Le backend ne démarre pas. |
| Pas sous forme d'origine exacte (`/` final, chemin, majuscules, `:443`) | Le backend ne démarre pas et indique la valeur à mettre. |
| Mauvais domaine, `http` au lieu de `https`, port en trop ou oublié par rapport à ce que tapent les gens | Les pages s'affichent, mais **toute connexion depuis le navigateur échoue** (`403 ORIGIN_NOT_ALLOWED`, ou 403 sans corps renvoyé par le filtre CORS), ainsi que le rafraîchissement de session et les actions admin. |
| Le site est joignable par deux adresses (domaine + IP locale) | Seule l'adresse de `PUBLIC_URL` permet de se connecter. Ajouter l'autre dans `CORS_ORIGINS` si besoin. |

Ce qui ne dépend **pas** de `PUBLIC_URL` : les clients hors navigateur (`curl`, Swagger ouvert à la bonne adresse, future app Android), qui n'envoient pas d'en-tête `Origin`.

### Cookies et HTTPS

La session du navigateur tient dans un cookie `HttpOnly; Secure; SameSite=Strict`. `Secure` impose le HTTPS : pour un essai en HTTP (`http://localhost:8080`), mettre `COOKIE_SECURE=false`, sinon la connexion semble réussir mais la session est perdue au rechargement de la page.

### Vraie IP des clients (anti brute force)

Les tentatives de connexion sont limitées par IP. Pour que le backend voie la vraie IP derrière le reverse proxy DSM sans qu'un client puisse en inventer une :

- `TRUSTED_PROXY_IPS` = l'adresse par laquelle le DSM arrive sur nginx, c'est-à-dire la passerelle du réseau Docker (`DOCKER_SUBNET` avec `.1` à la fin, `172.30.64.1` par défaut) ;
- après chaque changement réseau, lancer depuis la racine du dépôt, sur le NAS :
  `ADMIN_USER=admin ADMIN_PASSWORD='…' scripts/check-client-ip.sh` → trois lignes `OK` attendues.

## Scan de la bibliothèque

Le scan lit `/media` (en lecture seule), reconnaît animés, saisons et épisodes d'après les noms de fichiers (règles : `docs/ARCHITECTURE.md` §7) et remplit la base. Il ne touche jamais aux fichiers.

1. Se connecter en admin (`POST /api/auth/login`) et récupérer `accessToken`.
2. `POST /api/admin/library/scan` → `202 {"scanId": …}`. Le scan tourne en tâche de fond ; un second lancement pendant ce temps répond `409`.
3. `GET /api/admin/library/scan-report` → statut (`RUNNING`, `SUCCESS`, `FAILED` + raison), compteurs par catégorie, durée, nombre de problèmes par catégorie.
4. `GET /api/admin/library/issues?category=UNRESOLVED&anime=naruto` → liste des fichiers signalés (catégories : `UNRESOLVED`, `DUPLICATE`, `MULTI_EPISODE`, `DECIMAL_EPISODE`, `SEASON_MISMATCH`, `MISSING`, `UNREADABLE`), avec leur `mediaFileId`.
5. Correction : `PUT /api/admin/library/files/{mediaFileId}/override` avec `{"action":"EPISODE","animeTitle":"…","seasonNumber":1,"episodeNumber":7}` (ou `{"action":"EXTRA"}`, `{"action":"IGNORE"}`), puis relancer un scan. La correction n'est jamais écrasée ; `DELETE` sur la même URL l'annule.

Un scan abandonné (dossier média absent, vide ou illisible) ne marque rien comme disparu. Si un scan rendrait indisponibles **plus de la moitié** des fichiers connus (mauvais dossier monté, partage absent…), il s'arrête aussi sans rien modifier et le rapport l'explique ; si c'est voulu : `POST /api/admin/library/scan?confirmMassRemoval=true`. Un fichier disparu est seulement masqué : s'il revient, son épisode réapparaît avec le même id.

## Tester le scan complet (bibliothèque factice)

Pour tester sans les vrais fichiers : une copie de l'arborescence réelle en **fichiers vides** (≈ 33 000, tirés de `library-sample.txt`), créée dans un **volume Docker**. Les fichiers sont créés sous Linux, dans un conteneur, parce que certains noms sont interdits sous Windows.

```powershell
.\scripts\generate-fake-library.ps1                          # Linux : scripts/generate-fake-library.sh
docker compose -f docker-compose.yml -f docker-compose.fake-media.yml up -d --build
```

⚠️ Tant que vous testez avec le volume factice, **passez toujours les deux `-f`**, à chaque commande `docker compose` (`up`, `restart`, `logs`…). Sinon Compose recrée le backend avec `MEDIA_PATH` et le scan suivant s'arrête sur le garde-fou de disparition massive (rien n'est modifié). Pour ne pas y penser, ajouter dans `.env` : `COMPOSE_FILE=docker-compose.yml;docker-compose.fake-media.yml` (séparateur `;` sous Windows, `:` sous Linux).

Puis lancer un scan (voir ci-dessus) et regarder dans le rapport : durée (`stats.durationMs`), compteurs, problèmes. Relancer un scan : `newFiles` et `missing` doivent valoir 0. Pour simuler un fichier renommé ou disparu, modifier le volume depuis un conteneur :

```powershell
docker run --rm -v anime-fake-media:/media alpine:3 mv "/media/Genshiken/Saison 1/Genshiken 01X01.mkv" "/media/Genshiken/Saison 1/Genshiken 01X01 [v2].mkv"
```

Repartir de zéro : relancer le script (il vide le volume) ; supprimer : `docker volume rm anime-fake-media`. Sous Linux, le script accepte aussi un dossier : `scripts/generate-fake-library.sh "$PWD/fake-media"` (`fake-media/` est ignoré par git).

## Swagger / OpenAPI

- En dev : <http://localhost:8080/q/swagger-ui>, toujours actif.
- Avec Docker : mettre `SWAGGER_ENABLED=true` dans `.env`, relancer, puis `http://<hôte>:<WEB_PORT>/q/swagger-ui`. À remettre à `false` ensuite.
