# Anime Server

Serveur de streaming d'animés auto-hébergé, pour un petit groupe privé, sur un NAS Synology (Docker).
Backend Quarkus + PostgreSQL, interface web Angular servie par nginx.

- Architecture et décisions : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Avancement : [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Spike vidéo (phase 0) : [`docs/SPIKE.md`](docs/SPIKE.md)

> État : **phase 4 (interface web)** : connexion, accueil, bibliothèque, fiche anime et administration (scan, rapport, corrections, utilisateurs). Pas encore de lecteur vidéo.

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
- Propriétés de configuration propres à l'application : toujours sous le préfixe **`anime.`** (`anime.auth.*`, `anime.library.*`, `anime.spike.stream.*`). Quarkus refuse de démarrer si une propriété inconnue apparaît sous un préfixe mappé, et Maven ou la JVM définissent des propriétés système génériques (sous Windows, `mvnw quarkus:dev` définit `library.jansi.path`, qui bloquait le démarrage avec `SRCFG00050` quand le préfixe était `library`). Ne pas créer de préfixe générique (`library`, `auth`, `app`…). Les variables d'environnement (`MEDIA_ROOT`, `JWT_SECRET`…) ne changent pas. Test : `MavenSystemPropertiesTest`.

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

Puis ouvrir <http://localhost:8080> (ou `WEB_PORT`) : la page de connexion s'affiche. Se connecter avec l'admin initial, puis *Administration → Scan → Lancer un scan*.

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

La session du navigateur tient dans un cookie `HttpOnly; Secure; SameSite=Strict`. `Secure` impose le HTTPS et n'est pas désactivable avec Docker (le backend refuse de démarrer sinon). Deux cas :

- `http://localhost:8080` sur la machine qui fait tourner Docker : fonctionne, les navigateurs acceptent les cookies `Secure` sur `localhost` ;
- adresse en HTTP depuis une autre machine (`http://192.168.1.20:8080`) : la connexion semble réussir mais la session est perdue au rechargement (F5). Passer par le reverse proxy HTTPS du DSM.

En développement (`mvnw quarkus:dev`), `Secure` est désactivé par défaut (`COOKIE_SECURE=false`).

### Vraie IP des clients (anti brute force)

Les tentatives de connexion sont limitées par IP. Pour que le backend voie la vraie IP derrière le reverse proxy DSM sans qu'un client puisse en inventer une :

- `TRUSTED_PROXY_IPS` = l'adresse par laquelle le DSM arrive sur nginx, c'est-à-dire la passerelle du réseau Docker (`DOCKER_SUBNET` avec `.1` à la fin, `172.30.64.1` par défaut) ;
- après chaque changement réseau, lancer depuis la racine du dépôt, sur le NAS :
  `ADMIN_USER=admin ADMIN_PASSWORD='…' scripts/check-client-ip.sh` → trois lignes `OK` attendues.

## Scan de la bibliothèque

Le scan lit `/media` (en lecture seule), reconnaît animés, saisons et épisodes d'après les noms de fichiers (règles : `docs/ARCHITECTURE.md` §7) et remplit la base. Il ne touche jamais aux fichiers.

Dans l'interface : *Administration* → **Scan** (lancement, état, historique), **Rapport** (résumé par catégorie, fichiers signalés, bouton *Corriger*), **Corrections** (corrections enregistrées, annulables). Le même parcours avec l'API :

1. Se connecter en admin (`POST /api/auth/login`) et récupérer `accessToken`.
2. `POST /api/admin/library/scan` → `202 {"scanId": …}`. Le scan tourne en tâche de fond ; un second lancement pendant ce temps répond `409`.
3. `GET /api/admin/library/scan-report` → statut (`RUNNING`, `SUCCESS`, `FAILED` + `failureCode` et raison), compteurs par catégorie, durée, nombre de problèmes par catégorie. Historique : `GET /api/admin/library/scans`.
4. `GET /api/admin/library/issues?category=UNRESOLVED&anime=naruto` → liste des fichiers signalés (catégories : `UNRESOLVED`, `DUPLICATE`, `MULTI_EPISODE`, `DECIMAL_EPISODE`, `SEASON_MISMATCH`, `MISSING`, `UNREADABLE`), avec leur `mediaFileId`. Pour `DUPLICATE` : saison, épisode, fichier écarté (`relativePath`), fichier conservé (`keptRelativePath`) et origine de la saison de chacun (`seasonSource`, `keptSeasonSource` : nom, dossier, défaut…).
5. Correction : `PUT /api/admin/library/files/{mediaFileId}/override` avec `{"action":"EPISODE","animeTitle":"…","seasonNumber":1,"episodeNumber":7}` (ou `{"action":"EXTRA"}`, `{"action":"IGNORE"}`), puis relancer un scan. La correction n'est jamais écrasée ; `DELETE` sur la même URL l'annule. Si l'épisode est déjà fourni par un autre fichier : `409` avec le détail, rien n'est modifié ; `?replace=true` confirme le remplacement (l'interface demande cette confirmation).

Un scan abandonné (dossier média absent, vide ou illisible) ne marque rien comme disparu. Si un scan rendrait indisponibles **plus de la moitié** des fichiers connus (mauvais dossier monté, partage absent…), il s'arrête aussi sans rien modifier et le rapport l'explique ; si c'est voulu : `POST /api/admin/library/scan?confirmMassRemoval=true`. Un fichier disparu est seulement masqué : s'il revient, son épisode réapparaît avec le même id.

## Lecture (API)

Pas encore de lecteur web : la lecture se teste avec `curl` (ou VLC, qui accepte une URL).

```sh
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"login":"admin","password":"…"}' http://localhost:8080/api/auth/login | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/episodes/42/stream-url
# → {"url":"/api/stream/1234?u=1&exp=…&sig=…","expiresAt":"…","mimeType":"video/x-matroska","fileSize":…}
curl -s -o /dev/null -D - -H 'Range: bytes=0-1023' "http://localhost:8080/api/stream/1234?u=1&exp=…&sig=…"   # 206
```

L'URL de lecture est valable 6 h (`STREAM_URL_LIFETIME`), pour un seul fichier et un seul utilisateur ; elle ne demande pas d'en-tête d'authentification (un lecteur vidéo ne sait pas en envoyer). Sur un `403`, en redemander une.

Progression (par utilisateur) : `PUT /api/episodes/42/progress` avec `{"positionSeconds":600,"durationSeconds":1420}`, puis `GET /api/me/continue-watching` (épisodes commencés, pas encore terminés : au-delà de 90 %, l'épisode est terminé).

## Métadonnées (affiches, synopsis, année)

Récupérées sur [AniList](https://anilist.co) (API publique, sans clé) par une tâche de fond, séparée du scan : la bibliothèque et la lecture fonctionnent sans (visuel de remplacement, pas de synopsis).

- **Durée** : AniList limite le débit (30 requêtes/min en ce moment) ; la tâche fait un appel toutes les 2,5 s. Premier lancement sur ~1 300 animés : **environ une heure** ; ensuite, seulement les nouveaux animés, à la fin de chaque scan. Elle reprend où elle en était après un redémarrage.
- **Accès Internet sortant** du conteneur backend vers `graphql.anilist.co` nécessaire (le cas par défaut avec Docker). Sans accès, rien ne casse : la tâche réessaie plus tard. `METADATA_ENABLED=false` la désactive.
- **Synopsis en anglais** (AniList n'en a pas d'autre). Le titre de l'animé reste le nom du dossier ; le titre anglais ou romaji s'affiche en dessous.
- **Affiches** : seule l'URL est stockée ; le navigateur les charge depuis `s4.anilist.co` (autorisé par la CSP : `img-src https:`). AniList voit donc l'adresse IP des spectateurs ; les télécharger sur le NAS est noté dans `docs/FUTURE.md`.
- **Administration → Métadonnées** : avancement, non appariés et douteux, correction par candidat ou par identifiant AniList (le nombre dans `anilist.co/anime/<id>`), « aucune fiche ». Une correction est verrouillée : jamais écrasée. Remplacer une fiche existante demande une confirmation.

### Synopsis en français (TMDB, facultatif)

Sans configuration, les synopsis restent en anglais. Pour les avoir en français quand ils existent :

1. Créer un compte sur [themoviedb.org](https://www.themoviedb.org), puis *Paramètres → API* : demander une clé (usage personnel, non commercial).
2. Copier le **jeton d'accès en lecture** (« API Read Access Token », le long) dans `.env` : `TMDB_READ_TOKEN=…`, puis `docker compose up -d`.
3. *Administration → Synopsis français* : avancement (après AniList, quelques minutes), et animés restés sans synopsis français, à corriger à la main au besoin (adresse de la fiche TMDB).

Le jeton reste côté serveur (jamais envoyé au navigateur ni écrit dans les logs). **Conditions TMDB** : usage non commercial ; rien n'est conservé plus de 6 mois (fiches redemandées à 5 mois, effacées à 6) ; logo et mention dans *À propos* (déposer le logo officiel dans `web/public/attribution/tmdb-logo.svg`, voir le README de ce dossier) ; pour arrêter, bouton « Effacer toutes les données TMDB » puis retirer le jeton.

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
