# Architecture — Anime Server

> Statut : **validé** le 2026-09-29, avec les ajustements demandés (anti brute force, tolérance de rotation, chaîne d'IP, robustesse du scan, rebranchement d'épisode).

## 1. Vue d'ensemble

```
 Téléphone Android / navigateur
            │ HTTPS
            ▼
 Reverse proxy (DSM ou autre)  ── termine le TLS
            │ HTTP
            ▼
 ┌─────────────── docker compose ───────────────┐
 │  web (nginx)                                  │
 │   ├─ /        → fichiers statiques Angular    │
 │   └─ /api/*   → proxy vers backend:8080       │
 │                                               │
 │  backend (Quarkus, JVM)                       │
 │   ├─ REST JSON /api/*                         │
 │   └─ lit /media en lecture seule              │
 │                                               │
 │  postgres (volume pgdata)                     │
 └───────────────────────────────────────────────┘
        ▲
        └── ${MEDIA_PATH}:/media:ro  (dossier du propriétaire du NAS)
```

- **Un seul port exposé** : celui de `web` (nginx). Le backend et PostgreSQL ne sont pas publiés sur l'hôte.
- **Même origine** pour le front et l'API (`/` et `/api` derrière le même nginx) → pas de CORS en production, cookies `SameSite=Strict` possibles. CORS reste désactivé par défaut, avec une liste blanche optionnelle `CORS_ORIGINS` pour le dev.
- Le backend est la seule source de vérité ; Angular n'est qu'une vue.

## 2. Monorepo

La racine du dépôt git (`plex-wish/`) **est** la racine du monorepo décrit dans CLAUDE.md (`anime-server/`). Je ne crée pas de sous-dossier supplémentaire ; les artefacts (Maven, images Docker) s'appellent `anime-server-*`.

```
plex-wish/
├── backend/              Quarkus (Maven)
├── web/                  Angular
├── android/spike/        App Kotlin jetable (phase 0 uniquement)
├── dev-media/            Vidéos de test locales (contenu ignoré par git, .gitkeep versionné)
├── docs/                 ARCHITECTURE, ROADMAP, SPIKE, FUTURE
├── docker-compose.yml
├── .env.example
├── .gitattributes        Fins de ligne normalisées (dev sous Windows, build Linux)
└── README.md
```

## 3. Backend

### 3.1 Stack
Java 21, Quarkus (dernière LTS au moment de l'init), Maven.
Extensions : `quarkus-rest` + `quarkus-rest-jackson`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-jwt` + `quarkus-smallrye-jwt-build`, `quarkus-smallrye-openapi`, `quarkus-hibernate-validator`, `quarkus-elytron-security-common` (bcrypt), `quarkus-junit5` + `rest-assured`.

Exécution en **mode JVM** (pas de natif : build lourd, gain inutile ici). Le DS923+ (Ryzen R1600, 4 Go de RAM par défaut) supporte sans problème une JVM avec `-Xmx512m`.

### 3.2 Organisation du code
Découpage **par fonctionnalité**, trois couches au plus (resource → service → entité Panache). Pas d'interface « repository » ni de mapper générique tant qu'il n'y en a pas besoin.

```
fr.plexwish.animeserver
├── auth/       login, refresh, logout, JWT, refresh tokens, anti brute force
├── user/       User, rôles, gestion admin des utilisateurs, admin initial
├── library/    Anime, Season, Episode, MediaFile, scan, FilenameParser, rapport
├── stream/     (spike puis streaming définitif) Range + URL signées
└── common/     config, gestion d'erreurs (ExceptionMapper → JSON uniforme), sécurité média
```

Les entités ne sont jamais renvoyées directement : les resources exposent des DTO (records Java).

### 3.3 Identifiants
`BIGINT GENERATED ALWAYS AS IDENTITY` partout. Toutes les routes métier exigent une authentification, donc des IDs devinables ne posent pas de problème de sécurité ; c'est le plus simple à lire et à déboguer.

### 3.4 Erreurs
Format unique : `{ "status": 404, "error": "NOT_FOUND", "message": "..." }`. Les exceptions non prévues donnent un 500 générique (détail uniquement dans les logs).

## 4. Base de données

PostgreSQL 16 en conteneur, schéma géré **uniquement par Flyway** (`quarkus.hibernate-orm.database.generation=none`, validation au démarrage en dev/test).

### 4.1 Schéma cible (étape 1)

| Table | Colonnes principales | Remarques |
|---|---|---|
| `app_user` | id, username (unique), email (unique, nullable), password_hash, role (`ADMIN`/`USER`), enabled, created_at, updated_at | `user` est un mot réservé PostgreSQL |
| `refresh_token` | id, user_id, token_hash (unique), expires_at, revoked_at, revoked_reason (`ROTATED`/`LOGOUT`/`USER_DISABLED`/`REUSE_DETECTED`), created_at, last_used_at | le token en clair n'est jamais stocké |
| `anime` | id, title, normalized_title (unique), alternative_title, synopsis, poster_url, year, metadata_provider_id, created_at | 5 dernières colonnes vides à cette étape |
| `season` | id, anime_id, season_number | unique (anime_id, season_number) |
| `media_file` | id, relative_path (unique), file_name, file_size, last_modified, container, available, missing_since, first_seen_at, last_seen_at | voir 4.2 |
| `episode` | id, season_id, episode_number, title, synopsis, duration_seconds, media_file_id, created_at | unique (season_id, episode_number) |
| `scan_run` | id, started_at, finished_at, status (`RUNNING`/`SUCCESS`/`FAILED`), stats (compteurs), triggered_by | un seul scan à la fois |
| `scan_issue` | id, scan_run_id, relative_path, reason | fichiers ignorés + raison |

Migrations : `V1__auth.sql` (app_user, refresh_token) en phase 1, `V2__library.sql` en phase 3. On ne modifie jamais une migration déjà commitée.

### 4.2 `relative_path` au lieu de `absolutePath`
CLAUDE.md prévoit `MediaFile.absolutePath`. Je propose de stocker le chemin **relatif à `/media`** (ex. `Frieren/Season 01/Frieren - S01E01.mkv`) et de reconstruire le chemin absolu avec la racine configurée (`MEDIA_ROOT=/media`).
Raison : si la racine change (autre point de montage, dev local vs conteneur), rien ne casse et le rescan ne recrée pas tout. L'API ne renvoie de toute façon aucun chemin aux clients.

## 5. Authentification

### 5.1 Flux retenu
- **Access token** : JWT signé, durée **15 min**, claims `sub` (id), `upn` (username), `groups` (rôle). Renvoyé dans le corps JSON de `/login` et `/refresh`, **gardé en mémoire** côté Angular (jamais dans `localStorage`/`sessionStorage`).
- **Refresh token** : 256 bits aléatoires (`SecureRandom`), stocké **haché en SHA-256** en base, durée **30 jours**. Envoyé dans un cookie :
  `refresh_token; HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
  (`Secure` désactivable en dev HTTP via `COOKIE_SECURE=false`.)
- **Rotation** à chaque `/refresh` : l'ancien est révoqué (`ROTATED`), un nouveau est émis.
- **Fenêtre de tolérance de 20 s** (configurable, `REFRESH_REUSE_GRACE_SECONDS`, 10–30 s) : si un token révoqué pour cause de rotation il y a moins de 20 s est représenté, c'est presque toujours une course légitime (deux onglets, double appel au démarrage, réseau mobile qui rejoue la requête). On renvoie alors **un nouvel access token sans émettre de nouveau refresh token ni toucher au cookie** : le navigateur a déjà reçu le bon cookie via la requête concurrente.
- **Réutilisation hors fenêtre** (ou d'un token révoqué pour une autre raison que la rotation) → vol probable : tous les refresh tokens de l'utilisateur sont révoqués (`REUSE_DETECTED`), réponse 401.
- Rechargement de page : Angular appelle `/api/auth/refresh` au démarrage (le cookie part tout seul) pour récupérer un access token.
- **Logout** : révoque le refresh token courant et efface le cookie.
- Désactiver un utilisateur révoque ses refresh tokens ; son access token reste valide au plus 15 min (acceptable ici).

Pourquoi pas tout en cookie de session ? Le couple « access en mémoire + refresh HttpOnly » protège contre le vol par XSS (le JS ne peut pas lire le refresh token) et reste utilisable plus tard par l'app Android (qui recevra le refresh token dans le corps de la réponse sur demande explicite, et le stockera chiffré — hors scope maintenant).

CSRF : le seul endpoint qui s'appuie sur le cookie est `/api/auth/*` ; `SameSite=Strict` + même origine suffisent. En complément, le backend vérifie que le header `Origin`, s'il est présent, correspond à `PUBLIC_URL`.

### 5.2 Signature JWT
**HS256** avec un secret `JWT_SECRET` (≥ 32 octets) fourni par `.env`. Plus simple à déployer sur Synology qu'une paire de clés RSA (pas de fichiers PEM à monter). Il n'y a qu'un seul service qui émet et vérifie les tokens, donc l'asymétrique n'apporte rien.
Si la config smallrye-jwt s'avère pénible en HS256, repli : RS256 avec une paire de clés générée au premier démarrage dans un volume.

### 5.3 Mots de passe
bcrypt (coût 12) via `BcryptUtil`. Longueur min. 10 caractères, max. 72 octets (limite bcrypt, validée en entrée). Jamais loggés ; les DTO de login ont un `toString()` qui masque le mot de passe.

### 5.4 Anti brute force
En mémoire (`ConcurrentHashMap`, nettoyage périodique), deux compteurs, **aucun par username seul** :
- par couple **(IP, username)** : 5 échecs / 15 min → ce couple est bloqué 15 min ;
- par **IP seule** : 20 échecs / 15 min (tous usernames confondus) → cette IP est bloquée 15 min.

Ainsi un attaquant ne bloque que **ses propres IP** : il ne peut pas verrouiller le compte admin pour l'admin légitime qui se connecte depuis une autre IP. Réponse 429 avec un message neutre, identique que le compte existe ou non. Un login réussi remet à zéro le compteur (IP, username).

Remise à zéro au redémarrage : acceptable pour ~10 utilisateurs. Limite connue : un attaquant disposant de nombreuses IP (botnet) contourne le compteur par IP ; les mots de passe forts (min. 10 caractères) restent la vraie protection.

### 5.4.1 Chaîne d'IP : DSM → nginx → backend
Tout ce qui précède n'a de sens que si le backend voit **la vraie IP du client**, et qu'aucun client ne peut la falsifier avec un faux `X-Forwarded-For`.

```
client ──► reverse proxy DSM ──► nginx (web) ──► backend
           ajoute XFF            real_ip :         lit X-Forwarded-For
                                 fait confiance    uniquement si la requête
                                 au XFF seulement  vient de nginx
                                 si la requête
                                 vient du DSM
```

- **nginx** (module `realip`) : `set_real_ip_from ${TRUSTED_PROXY_IPS}` (IP du reverse proxy DSM vue depuis le conteneur, en général la passerelle du réseau Docker, ex. `172.17.0.1` ; configurable), `real_ip_header X-Forwarded-For`, `real_ip_recursive on`. Ensuite nginx **remplace** le header vers le backend : `proxy_set_header X-Forwarded-For $remote_addr` (pas `$proxy_add_x_forwarded_for`, qui propagerait des valeurs falsifiées). Une requête qui arrive directement sur nginx (depuis le LAN, sans passer par le DSM) garde son IP réelle : son `X-Forwarded-For` est ignoré.
- **backend** : `quarkus.http.proxy.proxy-address-forwarding=true`, `allow-x-forwarded=true`, `trusted-proxies` limité au sous-réseau du réseau compose interne. L'IP utilisée par l'anti brute force et les logs est `remoteAddress()` après ce traitement.
- **Tests** :
  - backend (`@QuarkusTest`) : un `X-Forwarded-For` venant d'un proxy de confiance est pris en compte (deux IP falsifiées différentes → compteurs séparés) ; avec un profil où l'appelant n'est pas de confiance, le header est ignoré ;
  - chaîne complète (phase 2, script `scripts/check-client-ip.sh` sur `docker compose`) : appel direct à nginx avec un faux `X-Forwarded-For` → le backend voit l'IP réelle ; appel « comme depuis le DSM » (IP de confiance) → le backend voit l'IP du header. Le backend expose pour cela `GET /api/admin/debug/client-ip` (ADMIN uniquement).
- ⚠️ À vérifier une fois sur le NAS : que le reverse proxy DSM ajoute bien `X-Forwarded-For` (il le fait par défaut ; sinon, en-tête personnalisé à ajouter dans DSM) et quelle IP il présente à nginx.

### 5.5 Admin initial
Au démarrage, si aucun `ADMIN` n'existe et que `INITIAL_ADMIN_USERNAME` / `INITIAL_ADMIN_PASSWORD` sont définis, l'admin est créé. Si aucun admin n'existe et que les variables manquent → log d'erreur explicite (sans bloquer le démarrage). Les variables sont ignorées ensuite : on peut les retirer du `.env` après le premier lancement.

### 5.6 Autorisations
`@RolesAllowed("ADMIN")` sur `/api/admin/*`, `@Authenticated` sur le reste de `/api/*` sauf `/api/auth/login|refresh|logout`. Test de permissions par endpoint (anonyme → 401, USER sur admin → 403).

## 6. Streaming — décision : **URL signée à courte durée de vie**

Problème : `<video src>` (et ExoPlayer sans configuration particulière) ne peut pas envoyer `Authorization: Bearer`.

Choix : l'app demande une URL de lecture authentifiée, puis le lecteur utilise cette URL telle quelle.

```
GET /api/episodes/{id}/stream-url      (Bearer JWT)
→ { "url": "/api/stream/{mediaFileId}?u={userId}&exp={epoch}&sig={hmac}", "expiresAt": ... }

sig = base64url(HMAC-SHA256(STREAM_SIGNING_SECRET, mediaFileId + ":" + userId + ":" + exp))
```

À chaque requête (y compris chaque requête Range lors d'un seek), le backend vérifie : signature (comparaison à temps constant), expiration, utilisateur toujours actif, fichier `available`. Le fichier est résolu **uniquement** via l'ID en base.

- **Durée de vie : 6 h.** Elle doit couvrir tout le visionnage, car le lecteur réutilise la même URL pour chaque seek. C'est court comparé au refresh token et l'URL ne donne accès qu'à un seul fichier.
- Pourquoi pas un cookie ? Il marcherait pour le web (même origine), mais ExoPlayer, un futur Chromecast ou un navigateur de TV le gèrent mal ou pas du tout. L'URL signée fonctionne partout de la même façon.
- Risque : l'URL peut fuiter via les logs (nginx, reverse proxy). Mitigation : format de log nginx sans query string pour `/api/stream/`, et le backend ne logge pas les query strings.
- `STREAM_SIGNING_SECRET` est distinct de `JWT_SECRET`.

**Rien de ceci n'est implémenté avant la fin de l'étape**, sauf l'endpoint Range du spike (phase 0).

### 6.1 Servir le fichier (Range Requests)
- Réponses `200` (sans Range), `206` + `Content-Range` (Range valide), `416` + `Content-Range: bytes */taille` (Range invalide), toujours `Accept-Ranges: bytes`.
- Une seule plage par requête (les lecteurs n'envoient jamais de multi-range ; on renvoie la première).
- Envoi depuis le disque sans charger le fichier en mémoire : Vert.x `HttpServerResponse.sendFile(path, offset, length)` (zero-copy côté OS). Repli : `StreamingOutput` avec `FileChannel.transferTo`.
- Types MIME : `.mp4/.m4v` → `video/mp4`, `.mkv` → `video/x-matroska`, `.webm` → `video/webm`, `.avi` → `video/x-msvideo`.

### 6.2 Risques de compatibilité vidéo (à vérifier par le spike)
Pour situer : un fichier vidéo = un **conteneur** (MKV, MP4…) qui contient des **pistes** encodées avec des **codecs** (vidéo, audio, sous-titres). Le lecteur doit comprendre le conteneur **et** chaque codec ; sinon il faut transcoder (convertir à la volée avec ffmpeg), ce qui est coûteux pour le Ryzen R1600 sans GPU.

| Élément | Situation fréquente pour l'anime | Android (ExoPlayer) | Navigateur (Chrome/Firefox) |
|---|---|---|---|
| Conteneur MKV | Très fréquent | ✅ | ⚠️ Chrome lit souvent le MKV « par accident », Firefox non, non garanti |
| Vidéo H.264 8 bits | Fréquent | ✅ | ✅ |
| Vidéo H.265/HEVC 10 bits | Très fréquent dans les releases récentes | ⚠️ dépend du décodeur matériel du téléphone | ❌/⚠️ dépend du matériel et du navigateur |
| Vidéo AV1 | En hausse | ⚠️ téléphones récents seulement | ✅ récents |
| Audio AAC / Opus | Fréquent | ✅ | ✅ |
| Audio FLAC | Parfois | ✅ | ✅ (sauf dans MKV sur certains) |
| Audio AC3/E-AC3/DTS | Plus rare en anime | ❌ souvent (extension ffmpeg nécessaire) | ❌ |
| Sous-titres ASS/SSA intégrés | Très fréquent (fansubs) | ⚠️ affichés sans styles avancés | ❌ pas en natif |

Conclusion : **Android est la cible la plus favorable** ; le navigateur est le plus risqué (MKV + HEVC + ASS). Le spike tranchera.

## 7. Bibliothèque

### 7.1 Scan
- Parcours récursif de `/media` (`Files.walkFileTree`), sans suivre les liens symboliques qui sortent de `/media`.
- Extensions vidéo reconnues : `mkv, mp4, m4v, webm, avi`. Les fichiers annexes connus (`.nfo, .jpg, .png, .srt, .ass, .txt`…) sont comptés mais pas listés individuellement dans le rapport ; les vidéos non reconnues et les extensions inconnues le sont, avec la raison.
- **Asynchrone** : `POST /api/admin/library/scan` renvoie `202` + l'id du `scan_run` ; un seul scan à la fois (`409` sinon). L'admin consulte `GET /api/admin/library/scan-report` (dernier scan : statut, compteurs, fichiers ignorés).
- Un fichier illisible ou mal nommé ne fait jamais échouer le scan.
- **Garde-fou montage** : avant de parcourir, le scan vérifie que `/media` existe, est lisible et **n'est pas vide**. Sinon il s'arrête en `FAILED` avec la raison (« /media vide ou illisible — montage NAS absent ? ») **sans rien marquer comme disparu**. Sans ce garde-fou, un volume non monté ferait passer toute la bibliothèque en indisponible. Même règle si le parcours lui-même échoue en cours de route (erreur d'I/O sur la racine) : aucun marquage « disparu » n'est appliqué pour ce scan.
- **Scans orphelins** : au démarrage du backend, tout `scan_run` resté `RUNNING` (conteneur arrêté pendant un scan) passe en `FAILED` avec la raison « interrompu par un redémarrage ». Sinon le verrou « un seul scan à la fois » resterait bloqué pour toujours.

### 7.2 Idempotence
- Clé d'un fichier : `relative_path`. Nouveau chemin → création ; chemin connu → mise à jour de `file_size`, `last_modified`, `last_seen_at`.
- Fichier connu absent du scan → `available=false`, `missing_since=now` (aucune suppression). S'il réapparaît → `available=true`, `missing_since=null`.
- Anime identifié par `normalized_title` (minuscules, espaces/ponctuation normalisés) ; saison par (anime, numéro) ; épisode par (saison, numéro).
- Deux fichiers **disponibles** pour le même épisode (ex. 720p + 1080p) : celui déjà lié est gardé, l'autre part dans le rapport (« doublon d'épisode »). Le choix de version → FUTURE.
- **Rebranchement** : si le `media_file` lié à un épisode est indisponible (`available=false`) et qu'un nouveau fichier est reconnu pour le même (anime, saison, numéro) — cas typique : fichier renommé ou remplacé par une meilleure version —, l'épisode est **rebranché** sur le nouveau fichier. L'ancien `media_file` reste en base, marqué indisponible. L'épisode garde son id (utile plus tard pour la progression de visionnage).
- L'API de lecture n'expose que les épisodes dont le fichier est `available`.

### 7.3 Parsing des noms
```java
interface FilenameParser {
    Optional<ParsedEpisode> parse(Path relativePath);   // relatif à /media
}
record ParsedEpisode(String animeTitle, int seasonNumber, int episodeNumber, String episodeTitle) {}
```
`CompositeFilenameParser` essaie les stratégies dans l'ordre ; la première qui reconnaît gagne, sinon le fichier va dans le rapport avec la raison « nom non reconnu ».

Stratégie livrée en phase 3 — `SeasonFolderParser` :
- `Titre/Season 01/<n'importe quoi> S01E05 <…>.mkv` (aussi `Saison 1`, `S1`, `s01e05`, casse indifférente) ;
- le titre vient du **dossier de premier niveau**, pas du nom de fichier ;
- incohérence dossier/nom (`Season 01` mais `S02E03`) → rapport.

Extensions prévues (non implémentées, notées dans FUTURE) : `ReleaseGroupAbsoluteParser` pour `[Groupe] Titre - 05 [1080p].mkv` (numérotation absolue → saison 1 par défaut), dossier `Specials`/`Season 00`, fichiers à plat sans dossier de saison.

## 8. API (étape 1)

| Méthode | Route | Accès |
|---|---|---|
| POST | `/api/auth/login` | public (rate limited) |
| POST | `/api/auth/refresh` | cookie refresh |
| POST | `/api/auth/logout` | cookie refresh |
| GET | `/api/me` | authentifié (utile au front pour le rôle) |
| GET | `/api/anime?sort=recent\|title` | authentifié |
| GET | `/api/anime/{id}` | authentifié |
| GET | `/api/anime/{id}/seasons` | authentifié |
| GET | `/api/seasons/{id}/episodes` | authentifié |
| GET | `/api/episodes/{id}` | authentifié |
| POST | `/api/admin/library/scan` | ADMIN |
| GET | `/api/admin/library/scan-report` | ADMIN |
| GET / POST | `/api/admin/users` | ADMIN |
| PATCH | `/api/admin/users/{id}` | ADMIN (activer/désactiver, rôle, mot de passe) |

Pas de pagination au départ (quelques centaines d'animes au plus) ; à ajouter si besoin. Un admin ne peut pas se désactiver ni retirer son propre rôle ADMIN (évite de se verrouiller dehors).
OpenAPI : `/q/openapi`, Swagger UI sur `/q/swagger-ui`, **actif en dev uniquement** par défaut (`SWAGGER_ENABLED`).

## 9. Front Angular

- Dernière version stable, **standalone components**, signals pour l'état local, `HttpClient`, Router avec lazy loading par page. SCSS avec variables CSS (thème sombre unique).
- Pas de bibliothèque UI. Éventuellement `@angular/cdk` (a11y : focus trap, gestion clavier) si le besoin apparaît — c'est léger et maintenu par Angular.
- Structure :
  ```
  src/app/
  ├── core/      AuthService (token en mémoire), interceptor (Bearer + refresh unique sur 401), guards (auth, admin), ApiService typés
  ├── pages/     login, home, library, anime-detail, admin
  └── shared/    composants simples (carte anime, bouton, loader)
  ```
- Services API écrits à la main (typés) : moins de tooling qu'un client généré, suffisant pour ~15 endpoints.
- Dev : `ng serve` avec `proxy.conf.json` qui redirige `/api` vers `localhost:8080` → même origine, pas de CORS.
- Accessibilité / navigation clavier : `:focus-visible` très marqué, cibles ≥ 48 px, ordre de tabulation logique, liens et boutons natifs (pas de `div` cliquables).

## 10. Docker / déploiement

- `backend` : build multi-étapes (Maven + JDK 21 → JRE 21), user non-root, `JAVA_OPTS=-Xmx512m`.
- `web` : build multi-étapes (Node → `nginx:alpine`), config nginx : fichiers statiques + fallback SPA vers `index.html` + `proxy_pass /api` + `real_ip` (voir §5.4.1) + log sans query string sur `/api/stream/`.
- `postgres` : `postgres:16-alpine`, volume nommé, non publié, healthcheck ; le backend attend qu'il soit sain.
- Montage média : `${MEDIA_PATH}:/media:ro`.
- ⚠️ **Permissions Synology** : le processus du conteneur backend doit pouvoir **lire** le dossier du propriétaire du NAS (ACL DSM). On expose `PUID`/`PGID` pour faire tourner le backend avec un utilisateur qui a le droit de lecture ; à documenter dans le README.
- Images construites localement ou sur le NAS (`docker compose build`) ; pas de registre à cette étape.

### Variables d'environnement (`.env.example`)
`MEDIA_PATH`, `WEB_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`, `STREAM_SIGNING_SECRET`, `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD`, `PUBLIC_URL`, `COOKIE_SECURE`, `CORS_ORIGINS`, `SWAGGER_ENABLED`, `PUID`, `PGID`, `TRUSTED_PROXY_IPS`, `REFRESH_REUSE_GRACE_SECONDS`, `DEV_SPIKE_STREAM_ENABLED` et `DEV_MEDIA_PATH` (dev uniquement).

## 11. Sécurité — récapitulatif
- Médias : lecture seule (montage `:ro` **et** aucune API d'écriture) ; accès uniquement par ID ; chemin résolu vérifié avec `toRealPath().startsWith(mediaRoot)` → test de path traversal (`../`, encodages, liens symboliques).
- Secrets uniquement via variables d'environnement ; démarrage refusé en prod si `JWT_SECRET`/`STREAM_SIGNING_SECRET` absents ou trop courts.
- Logs : jamais de mot de passe, de token, de cookie ni de query string de streaming.
- Validation des entrées (Bean Validation sur tous les DTO).
- Headers de sécurité posés par nginx : `Content-Security-Policy` stricte, `X-Content-Type-Options`, `Referrer-Policy: same-origin`, `X-Frame-Options: DENY`.

## 12. Tests
- Backend : JUnit 5 + RestAssured + `@QuarkusTest`. PostgreSQL de test via **Quarkus Dev Services** (Testcontainers) → nécessite Docker Desktop lancé sur la machine de dev. Pas de H2 (comportement différent de PostgreSQL).
- Tests obligatoires : Range Requests, path traversal, auth / permissions / brute force (dont : l'admin reste connectable depuis une autre IP), tolérance de rotation, chaîne d'IP, parsing, idempotence du rescan (dont : /media vide, scan orphelin, rebranchement d'épisode).
- Front : quelques tests unitaires ciblés (AuthService, interceptor, guards). Pas d'e2e à cette étape.

## 13. Décisions validées (2026-09-29)
1. `relative_path` au lieu de `absolutePath` (§4.2).
2. JWT en HS256 avec secret en variable d'environnement (§5.2).
3. Streaming par URL signée, durée 6 h, liée à l'utilisateur (§6).
4. Spike vidéo (phase 0) avant le socle.
5. Ajustements : brute force par (IP, username) + IP seule (§5.4), tolérance de rotation 20 s (§5.1), chaîne d'IP testée (§5.4.1), garde-fous du scan (§7.1), rebranchement d'épisode (§7.2).
