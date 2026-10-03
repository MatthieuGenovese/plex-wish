# Architecture — Anime Server

> Statut : **validé** le 2026-09-29, avec les ajustements demandés (anti brute force, tolérance de rotation, chaîne d'IP, robustesse du scan, rebranchement d'épisode).
> §7 réécrite le 2026-09-30 d'après la vraie bibliothèque (décisions en §14) : **en attente de relecture**.

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
| `media_file` | id, relative_path (unique), file_name, file_size, last_modified, container, kind (`EPISODE`/`EXTRA`/`UNRESOLVED`), available, missing_since, first_seen_at, last_seen_at | voir 4.2 et §7 |
| `media_file_override` | id, relative_path (unique), action (`EPISODE`/`EXTRA`/`IGNORE`), anime_title, season_number, episode_number, created_by, created_at | correction manuelle, jamais écrasée par un scan (§7.7) |
| `episode` | id, season_id, episode_number, title, synopsis, duration_seconds, media_file_id, created_at | unique (season_id, episode_number) |
| `scan_run` | id, started_at, finished_at, status (`RUNNING`/`SUCCESS`/`FAILED`), stats (compteurs par catégorie, JSON), triggered_by | un seul scan à la fois |
| `scan_issue` | id, scan_run_id, media_file_id, relative_path, category (`UNRESOLVED`/`DUPLICATE`/`MULTI_EPISODE`/`DECIMAL_EPISODE`/`SEASON_MISMATCH`/`MISSING`/`UNREADABLE`), anime_title, detail, season_number, episode_number, kept_relative_path, season_source, kept_season_source (V4) | liste filtrable du rapport (§7.6) |

Migrations : `V1__auth.sql` (app_user, refresh_token) en phase 1, `V2__refresh_token_password_reset.sql` en phase 2 (motif de révocation `PASSWORD_RESET`), `V3__library.sql` en phase 3. On ne modifie jamais une migration déjà commitée.

### 4.2 `relative_path` au lieu de `absolutePath`
CLAUDE.md prévoit `MediaFile.absolutePath`. Je propose de stocker le chemin **relatif à `/media`** (ex. `Frieren/Season 01/Frieren - S01E01.mkv`) et de reconstruire le chemin absolu avec la racine configurée (`MEDIA_ROOT=/media`).
Raison : si la racine change (autre point de montage, dev local vs conteneur), rien ne casse et le rescan ne recrée pas tout. L'API ne renvoie de toute façon aucun chemin aux clients.

## 5. Authentification

### 5.1 Flux retenu
- **Access token** : JWT signé, durée **15 min**, claims `sub` (id), `upn` (username), `groups` (rôle). Renvoyé dans le corps JSON de `/login` et `/refresh`, **gardé en mémoire** côté Angular (jamais dans `localStorage`/`sessionStorage`).
- **Refresh token** : 256 bits aléatoires (`SecureRandom`), stocké **haché en SHA-256** en base, durée **30 jours**. Envoyé dans un cookie :
  `refresh_token; HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
  (`Secure` désactivable en dev uniquement, via `COOKIE_SECURE=false` ; en prod le backend refuse de démarrer sans `Secure`. `http://localhost` reste utilisable : les navigateurs y acceptent les cookies `Secure`.)
- **Rotation** à chaque `/refresh` : l'ancien est révoqué (`ROTATED`), un nouveau est émis.
- **Fenêtre de tolérance de 20 s** (configurable, `REFRESH_REUSE_GRACE_SECONDS`, 10–30 s) : si un token révoqué pour cause de rotation il y a moins de 20 s est représenté, c'est presque toujours une course légitime (deux onglets, double appel au démarrage, réseau mobile qui rejoue la requête). On renvoie alors **un nouvel access token sans émettre de nouveau refresh token ni toucher au cookie** : le navigateur a déjà reçu le bon cookie via la requête concurrente.
- **Réutilisation hors fenêtre** (ou d'un token révoqué pour une autre raison que la rotation) → vol probable : tous les refresh tokens de l'utilisateur sont révoqués (`REUSE_DETECTED`), réponse 401.
- Rechargement de page : Angular appelle `/api/auth/refresh` au démarrage (le cookie part tout seul) pour récupérer un access token.
- **Logout** : révoque le refresh token courant et efface le cookie.
- Désactiver un utilisateur révoque ses refresh tokens ; son access token reste valide au plus 15 min (acceptable ici).

Pourquoi pas tout en cookie de session ? Le couple « access en mémoire + refresh HttpOnly » protège contre le vol par XSS (le JS ne peut pas lire le refresh token) et reste utilisable plus tard par l'app Android, avec des endpoints dédiés où le refresh token passe dans le corps (§5.1.1).

CSRF : le seul endpoint qui s'appuie sur le cookie est `/api/auth/*` ; `SameSite=Strict` + même origine suffisent. En complément, **toute requête d'écriture sur `/api`** (POST, PUT, PATCH, DELETE) dont le header `Origin` est présent doit venir de `PUBLIC_URL` (ou de `CORS_ORIGINS` en dev), sinon `403 ORIGIN_NOT_ALLOWED` (`auth/OriginCheck`). Sans header `Origin` (curl, future app Android), la requête passe : l'authentification suffit.

`PUBLIC_URL` figure aussi dans les origines CORS autorisées. C'est indispensable : derrière nginx, le backend reçoit la requête en `http` alors que le navigateur annonce `Origin: https://…`, et le filtre CORS de Quarkus la traiterait sinon comme une autre origine (login impossible). `PUBLIC_URL` doit donc être l'origine exacte (`https://hôte[:port]`, sans `/` final) : le démarrage échoue sinon, en indiquant la valeur à mettre.

### 5.1.1 Clients non navigateur (app Android) — conception, non implémentée
Le cookie `HttpOnly; Secure; SameSite=Strict` est fait pour le navigateur : le JavaScript ne peut pas lire le refresh token, et le navigateur le gère seul. Une app Android native n'a pas ce mécanisme (OkHttp n'a pas de stockage de cookies persistant par défaut) et n'est pas exposée au même risque (pas de XSS). Décision proposée (2026-10-02) :

- **Endpoints dédiés `/api/auth/app/{login,refresh,logout}`**. Le refresh token est dans le **corps** (réponse de `login` et `refresh` : `{accessToken, expiresIn, refreshToken, user}` ; requête de `refresh` et `logout` : `{refreshToken}`). Ces endpoints **n'émettent ni ne lisent jamais de cookie**, et les endpoints du navigateur ne renvoient jamais le refresh token dans le corps.
- **Réservés aux clients non navigateur** : toute requête vers `/api/auth/app/*` qui porte un en-tête `Origin` est refusée (403). Un navigateur envoie toujours `Origin` sur un POST `fetch` ; une page web (ou un XSS) ne peut donc ni s'en servir, ni obtenir par là un refresh token. Ce n'est pas une preuve d'identité de l'app (un script `curl` peut imiter l'app), mais ce n'est pas le but : il faut de toute façon le mot de passe ou un refresh token valide.
- **Même logique serveur** que le web : même table `refresh_token` (haché SHA-256), 30 jours, rotation à chaque refresh, fenêtre de tolérance, révocation de toute la famille en cas de réutilisation, même anti brute force sur `login`, désactivation / changement de mot de passe qui révoquent tout. Ajout d'une colonne `client` (`WEB` / `ANDROID`) et d'un libellé d'appareil, pour lister et révoquer un appareil depuis l'admin (plus tard).
- **Stockage côté Android** : access token **en mémoire** uniquement. Refresh token chiffré en **AES-GCM avec une clé de l'Android Keystore** (non exportable, matérielle quand le téléphone le permet), le texte chiffré dans DataStore. `EncryptedSharedPreferences` (`androidx.security:security-crypto`) est déprécié : Keystore + Tink (ou Keystore directement) le remplace. Fichier **exclu des sauvegardes** (`dataExtractionRules` / `fullBackupContent`) : le token ne part ni dans la sauvegarde Google ni vers un nouveau téléphone.
- **Côté réseau Android** : un `Authenticator` OkHttp rafraîchit sur 401 avec **un seul refresh à la fois** (verrou), comme l'interceptor Angular ; HTTPS obligatoire (trafic en clair interdit dans la `network security config` de la version release). Pas de certificate pinning : le certificat du DSM (Let's Encrypt) change tous les trois mois. Le streaming n'est pas concerné : URL signée (§6), sans en-tête d'authentification pour ExoPlayer.
- **Logs** : le refresh token du corps ne doit jamais apparaître (`toString()` masqué, comme `LoginRequest` ; `LogLeakTest` étendu à ces endpoints).

Risques restants :
- **Téléphone rooté ou malveillant** : la clé Keystore n'est pas exportable mais un code root peut l'utiliser sur l'appareil et déchiffrer le token. Le voleur a alors au plus 30 jours d'accès ; la rotation limite la durée (si le vrai téléphone rafraîchit après le vol, la réutilisation est détectée et toute la famille est révoquée).
- **Téléphone perdu ou volé** : l'admin désactive le compte ou change le mot de passe (révocation immédiate des refresh tokens, l'access token expire en 15 min). La révocation par appareil arrivera avec la colonne `client`.
- **Rotation et réseau mobile** : une réponse de refresh perdue (coupure) laisse l'app avec un token déjà remplacé ; la fenêtre de tolérance couvre le cas où l'app le renvoie vite. Au-delà, l'utilisateur doit se reconnecter (acceptable).
- **Faux client** : un script peut appeler `/api/auth/app/*` (pas d'en-tête `Origin`). Ce n'est pas une faille : c'est exactement ce que permettent déjà `curl` et Swagger avec un mot de passe.

### 5.2 Signature JWT
**HS256** avec un secret `JWT_SECRET` (≥ 32 octets) fourni par `.env`. Plus simple à déployer sur Synology qu'une paire de clés RSA (pas de fichiers PEM à monter). Il n'y a qu'un seul service qui émet et vérifie les tokens, donc l'asymétrique n'apporte rien.
Mise en œuvre (phase 2) : l'extension Quarkus ne sait pas lire un secret HS256 brut depuis une variable d'environnement. `auth/JwtKeys` construit donc une seule clé HMAC à partir de `JWT_SECRET` et l'utilise pour signer **et** pour produire la configuration de vérification (`JWTAuthContextInfo` : clé, émetteur `anime-server`, algorithme HS256 seul). Pas de repli RS256 nécessaire.

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

- **Réseau Docker à sous-réseau fixe** (`DOCKER_SUBNET`, défaut `172.30.64.0/24`) : sa passerelle `172.30.64.1` est connue à l'avance. Le reverse proxy DSM tourne sur l'hôte et se connecte au port publié : nginx le voit arriver depuis cette passerelle. Un client du LAN qui appelle directement le port publié garde en revanche sa vraie IP (règles iptables de Docker).
- **nginx** (module `realip`) : un `set_real_ip_from` par entrée de `TRUSTED_PROXY_IPS` (liste séparée par des virgules, défaut `172.30.64.1` = la passerelle ; générée au démarrage par `15-real-ip.sh`, qui refuse une entrée invalide ou `0.0.0.0/0` ; **vide = aucune confiance**), `real_ip_header X-Forwarded-For`, `real_ip_recursive on`. Ensuite nginx **remplace** le header vers le backend : `proxy_set_header X-Forwarded-For $remote_addr` (pas `$proxy_add_x_forwarded_for`, qui propagerait des valeurs falsifiées). Une requête qui arrive directement sur nginx (depuis le LAN, sans passer par le DSM) garde son IP réelle : son `X-Forwarded-For` est ignoré.
- **backend** : `quarkus.http.proxy.proxy-address-forwarding=true`, `allow-x-forwarded=true`, `trusted-proxies` = `DOCKER_SUBNET` (seul nginx, dans ce réseau, parle au backend, qui n'est pas publié). En dev (`quarkus:dev`, joignable depuis le LAN pour le spike), la lecture de `X-Forwarded-For` est désactivée. L'IP utilisée par l'anti brute force et les logs est `remoteAddress()` après ce traitement.
- **Tests** :
  - backend (`@QuarkusTest`) : un `X-Forwarded-For` venant d'un proxy de confiance est pris en compte (deux IP falsifiées différentes → compteurs séparés) ; avec un profil où l'appelant n'est pas de confiance, le header est ignoré ;
  - chaîne complète (`scripts/check-client-ip.sh`, stack `docker compose` démarrée ; s'appuie sur `GET /api/admin/debug/client-ip`, ADMIN uniquement) : (1) requête depuis nginx lui-même avec un faux `X-Forwarded-For` → ignoré ; (1b) requête depuis **un autre conteneur du réseau Docker** avec un faux `X-Forwarded-For` → ignoré (avec l'ancienne confiance à `172.16.0.0/12`, elle aurait été crue : vérifié) ; (2) requête depuis l'hôte, comme le DSM, avec `X-Forwarded-For: 6.6.6.6, 203.0.113.9` → le backend lit `203.0.113.9`, l'adresse ajoutée par le proxy, pas la valeur forgée en tête.
- **Port publié sur `127.0.0.1` par défaut** (`WEB_BIND`, 2026-10-03) : seul l'hôte (le reverse proxy DSM) atteint nginx ; le LAN ne peut plus appeler le port en HTTP clair. Vérifié avec `check-client-ip.sh` (trois `OK`) : l'hôte arrive toujours par la passerelle `172.30.64.1`. L'adresse IPv6 `::1` n'écoute pas : la destination du reverse proxy doit être `127.0.0.1`, pas `localhost`. `WEB_BIND=0.0.0.0` rétablit l'ancien comportement (essai sans reverse proxy).
- ⚠️ Limite connue (sans objet avec `WEB_BIND=127.0.0.1`, le LAN n'atteignant plus le port) : si Docker fait passer aussi le trafic du LAN par son *userland proxy* (configuration inhabituelle), un client du LAN arriverait par la passerelle, donc « de confiance ». À vérifier une fois sur le NAS : se connecter depuis un téléphone en 4G via le domaine et lire l'IP dans les logs du backend (`Connexion de '…' depuis <ip>`).
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

**Implémenté en phase 5** (`stream/StreamSigner`, `stream/StreamResource`) :
- `GET /api/episodes/{id}/stream-url` → `{url, expiresAt, mimeType, fileSize}`. L'URL est relative (`/api/stream/…`) : le client la colle à l'adresse du serveur qu'il connaît. `404 EPISODE_NOT_FOUND` (épisode inconnu), `404 EPISODE_UNAVAILABLE` (fichier absent du NAS).
- Signature : `HMAC-SHA256(STREAM_SIGNING_SECRET, "v1:" + mediaFileId + ":" + userId + ":" + exp)`, base64url sans `=`. Le préfixe `v1` permettra de changer le format sans accepter d'anciennes URL par erreur. Durée : `STREAM_URL_LIFETIME` (6 h par défaut).
- `GET` et `HEAD /api/stream/{mediaFileId}?u=&exp=&sig=` : pas d'en-tête d'authentification. Ordre des contrôles : signature (`403 STREAM_URL_INVALID`), expiration (`403 STREAM_URL_EXPIRED`), utilisateur actif (`403 USER_DISABLED`, relu en base à chaque requête), fichier disponible et chemin réel sous la racine (`404 EPISODE_UNAVAILABLE`). **Sur un 403, le client redemande une URL** (pause longue) ; s'il obtient à nouveau 403 ou 401, il renvoie à la connexion.
- Le chemin vient de la base, et il est revérifié : `..`, chemin absolu ou lien symbolique qui sortirait de la racine → 404 (`StreamTest`).
- `Cache-Control: private` : un cache partagé ne garde pas la vidéo d'un utilisateur.
- Logs : nginx journalise `$uri` (sans query string) et ne met pas `/api/stream/` en tampon (`proxy_buffering off`, sinon il recopierait la vidéo dans un fichier temporaire). Côté Quarkus, le format du journal d'accès (désactivé par défaut) utilise `%R`, le chemin sans query string, et le logger `ForwardedParser`, qui recopie l'URL complète en DEBUG, reste en INFO (`LogLeakTest`). ⚠️ Le reverse proxy du DSM a son propre journal : ne pas y activer les journaux détaillés.

### 6.1 Servir le fichier (Range Requests)
- Réponses `200` (sans Range), `206` + `Content-Range` (Range valide), `416` + `Content-Range: bytes */taille` (Range invalide), toujours `Accept-Ranges: bytes`.
- Une seule plage par requête (les lecteurs n'envoient jamais de multi-range ; on renvoie la première).
- Envoi depuis le disque sans charger le fichier en mémoire : Vert.x `HttpServerResponse.sendFile(path, offset, length)` (zero-copy côté OS). Repli : `StreamingOutput` avec `FileChannel.transferTo`.
- Types MIME : `.mp4/.m4v` → `video/mp4`, `.mkv` → `video/x-matroska`, `.webm` → `video/webm`, `.avi` → `video/x-msvideo`, `.ts` → `video/mp2t`, `.ogm` → `video/ogg`.

### 6.3 Progression de lecture (phase 5)
- Table `playback_progress` (V6) : clé (utilisateur, épisode), position et durée en secondes, `completed`, `updated_at`. Une ligne par utilisateur et par épisode : les progressions de deux utilisateurs sont indépendantes.
- `PUT /api/episodes/{id}/progress` `{positionSeconds, durationSeconds}` : le lecteur l'appelle régulièrement (toutes les 10 à 30 s, et à la pause / fermeture). Une position au-delà de la durée est ramenée à la durée. **Terminé au-delà de 90 %** de la durée (au-delà, pas à 90 % pile) : génériques de fin et aperçu de l'épisode suivant ne comptent pas. Revenir en arrière remet l'épisode « en cours ». 404 si l'épisode n'est pas visible.
- `GET /api/me/progress[?animeId=]` : toute la progression de l'utilisateur (ou d'un animé, pour sa fiche), la plus récente d'abord.
- `GET /api/me/continue-watching[?limit=20]` : épisodes commencés (position > 0) et non terminés, du plus récemment regardé au plus ancien, avec l'animé, la saison (libellé compris) et l'épisode : de quoi afficher la liste et relancer la lecture sans autre appel.
- Un épisode devenu indisponible disparaît des listes ; sa progression est conservée et revient avec le fichier.
- La durée envoyée par le lecteur n'est pas recopiée dans `episode.duration_seconds` : elle vient d'un client, la durée « officielle » viendra de ffprobe (FUTURE).

### 6.4 Derrière le reverse proxy du DSM (2026-10-03)
- **Mise en tampon** : un nginx réglé par défaut (le reverse proxy du DSM en est un) recopie une réponse dans un fichier temporaire quand le client lit moins vite que l'amont n'envoie, jusqu'à 1 Go par requête. Mesuré avec un nginx réglé comme le DSM devant l'application : **1 Go écrit en 5 s** pour un client à 300 Ko/s. Le nginx de l'application envoie donc `X-Accel-Buffering: no` sur `/api/stream/` (nginx le respecte et ne le transmet pas au navigateur) : plus de fichier temporaire, seek identique (206 en ~10 ms au milieu d'un fichier de 3 Go).
- **Pause longue** : après ~60 s de pause, la connexion est fermée (délai d'envoi par défaut de nginx, des deux côtés ; indépendant des délais réglables du DSM, mesuré). Le lecteur doit rouvrir une requête `Range` à la bonne position.
- **À tester en phase 7 (lecteur)**, sur téléphone en 4G à travers le DSM : lancer la lecture, pause de plus de 60 s, reprise ; vérifier que le lecteur rouvre la connexion sans erreur visible et repart **au bon endroit** (pas au début), et que la progression enregistrée reste juste. Même test avec un seek pendant la pause.

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

### 7.0 Ce que montre la vraie bibliothèque (2026-09-30)
Relevé complet du NAS : 32 940 fichiers, **28 254 vidéos**, **1 317 animés** (un dossier de premier niveau par animé, aucun fichier à la racine). Le relevé brut contient un nom de personne : il reste dans `docs/private/` (ignoré par git). Le jeu de test versionné en est dérivé : `backend/src/test/resources/library-sample.txt` (chemins relatifs à la racine, UTF-8 sans BOM, un chemin par ligne).

| Constat | Chiffre | Conséquence |
|---|---|---|
| Vidéos sans dossier de saison (aucun dossier dont le nom commence par `Season`, `Saison` ou `S` + numéro) | 21 666 / 28 254 (≈ 3/4) | Le dossier ne peut pas être une condition d'import |
| Dossier de saison qui contredit le nom du fichier (`SxxExx` / `NxEE`) | 116 / 6 081 | Le nom du fichier gagne, le désaccord va au rapport |
| Épisodes `S00` rangés dans un dossier OAV / OVA / Special / Bonus | 190 | Saison 0 = « Spéciaux » |
| Vrais épisodes numérotés dont le nom contient « OAV », « OVA », « Bonus » ou « Extra » | 62 | Un numéro d'épisode l'emporte sur ces mots |
| Doublons d'épisode `SxxExx` / `NxEE` (même animé, saison, épisode) | 12, dont 10 pour Devilman Crybaby | Un fichier gardé, l'autre signalé |
| Fichiers qui dépendent de la règle « numéro seul » précisée en §7.2 (nombre précédé d'un espace ou d'un `_`) | ≈ 210 (One Piece de Kaerizaki-Fansub, Naruto Kai…) | Règle documentée exactement, tests dédiés |
| Sous-titres externes (`.ass`, `.sup`, `.srt`) | 434, dont 24 avec le même nom de base qu'une vidéo | Pas d'association dans le MVP |

Chiffres mesurés par un script d'analyse jetable (hors dépôt) sur le relevé : ce sont des ordres de grandeur, qui bougent de quelques unités selon la définition exacte retenue. Les chiffres qui font foi seront ceux des tests de la phase 3.

Extensions vidéo trouvées : `mkv` (21 174), `mp4` (6 148), `avi` (895), `ogm` (28), `ts` (10). ⚠️ Compatibilité : AVI et MPEG-TS sont lus par ExoPlayer (AVI validé par le spike) ; **OGM** (vieux format vidéo dans un conteneur Ogg) n'est probablement lu ni par ExoPlayer ni par les navigateurs → ces 28 fichiers sont importés normalement, leur lecture sera à tester.

### 7.1 Scan
- Parcours récursif de `/media` (`Files.walkFileTree`) **sans suivre aucun lien symbolique** (ceux qui sortent de `/media` compris) : ils sont seulement comptés (`symlinksSkipped`). Plus simple et plus sûr que de vérifier la cible de chacun.
- La JVM doit encoder les noms de fichiers en UTF-8 (`sun.jnu.encoding`) : c'est le cas dans l'image Docker (`LANG=en_US.UTF-8`) ; sinon un avertissement est loggé au démarrage, car les noms accentués ou japonais seraient illisibles sous Linux.
- Ignorés sans bruit : dossiers techniques Synology `@eaDir`, `#recycle`, `#snapshot` ; fichiers AppleDouble `._*` ; fichier dont le nom se réduit à l'extension (`.mkv`).
- Vidéos : `mkv, mp4, avi, ogm, ts, m4v, webm`. Les autres fichiers (images, musique, polices, archives, sous-titres…) sont **comptés par type** dans le rapport, pas listés un par un.
- **Asynchrone** : `POST /api/admin/library/scan` renvoie `202` + l'id du `scan_run` ; un seul scan à la fois (`409` sinon), garanti par la base (index unique partiel sur `status = 'RUNNING'`). L'admin consulte `GET /api/admin/library/scan-report`.
- Un fichier illisible ou mal nommé ne fait jamais échouer le scan.
- **Garde-fou montage** : avant de parcourir, le scan vérifie que `/media` existe, est lisible et **n'est pas vide**. Sinon il s'arrête en `FAILED` avec la raison (« /media vide ou illisible — montage NAS absent ? ») **sans rien marquer comme disparu**. Même règle si le parcours échoue sur la racine, ou s'il ne trouve **aucune vidéo**.
- **Garde-fou disparition massive** : si le scan rendrait indisponibles **plus de la moitié** des fichiers connus (disponibles avant le scan), il s'arrête en `FAILED` **avant toute écriture** (ni fichier marqué disparu, ni animé créé, ni épisode rebranché) avec un message qui donne le nombre, le pourcentage et la marche à suivre. Cas typiques : mauvais dossier monté, sous-dossier ou partage NAS absent. Si c'est voulu (le propriétaire a vraiment retiré beaucoup de fichiers), l'admin relance avec `POST /api/admin/library/scan?confirmMassRemoval=true` ; le rapport l'indique (`stats.massRemovalConfirmed`). Le contrôle est fait avant le premier lot, car le scan connaît déjà la liste complète des fichiers vus.
- **Scans orphelins** : au démarrage, tout `scan_run` resté `RUNNING` passe en `FAILED` (« interrompu par un redémarrage »).
- **Performance** (premier scan ≈ 28 000 vidéos) :
  - traitement **par lots** de 500 vidéos (`anime.library.batch-size`), une transaction par lot. Animés, saisons, épisodes et corrections sont chargés en mémoire une fois au début ; chaque lot écrit avec quelques requêtes **multi-lignes** en JDBC (`INSERT … ON CONFLICT … RETURNING`, `UPDATE … FROM (VALUES …)`). Hibernate n'est pas utilisé pour le scan : il ne sait pas regrouper des insertions sur des clés `IDENTITY` ;
  - fichiers disparus : **une seule** requête en fin de scan (`last_seen_at` antérieur au début du scan) ;
  - mesuré sur la vraie liste (28 254 vidéos en fichiers vides) : **≈ 4,5 s** pour le premier scan, **≈ 3 s** pour un rescan, sur la machine de développement (le NAS sera plus lent, surtout pour le parcours du disque) ;
  - le parsing est pur calcul sur des chaînes (aucun accès disque au-delà du `stat` du parcours) ;
  - **ffprobe n'est pas appelé pendant le scan** (plusieurs heures sur 28 000 fichiers) : codecs et durée seront lus plus tard, à la demande ou par une tâche séparée (voir FUTURE).

### 7.2 Identification d'un épisode — le nom du fichier fait foi
Le **nom du fichier** est la source de vérité. Le **dossier de saison** (`Season 2`, `Saison 02`, `S2`…) ne sert que :
- de **secours**, quand le nom ne donne pas la saison (stratégie 3) ;
- de **vérification** : si nom et dossier donnent deux saisons différentes, le nom l'emporte et le désaccord est ajouté au rapport.

Il n'est **jamais** une condition d'import. Le **titre de l'animé** vient du **dossier de premier niveau** (`Frieren/…` → « Frieren »), jamais du nom de fichier.

Avant d'appliquer les stratégies, on neutralise les éléments techniques qui contiennent des chiffres : groupes entre crochets/parenthèses en début de nom (`[SubsPlease]`), résolutions (`1080p`, `1920x1080`), codecs (`x264`, `x265`, `H.264`, `10bits`, `AAC2.0`…), empreintes CRC (`[DBB79FF9]`). Sinon « One Piece Kaï - 098 - … - 1080p.x264 » donnerait l'épisode 264.

Ordre d'évaluation (`CompositeFilenameParser`), la première étape qui répond gagne :

1. `SxxExx` ;
2. `NxEE` ;
3. `E\d+` seul ;
4. **test d'extras** (§7.4) : uniquement pour un fichier qui n'a pas de numéro d'épisode d'après les étapes 1 à 3 ;
5. **numéro seul**.

Les étapes 1 à 3 passent **avant** le test d'extras : un fichier numéroté reste un épisode quels que soient son dossier (`OAV/`, `Bonus/`, `Extras/`) et les mots de son nom. Le test d'extras ne protège que l'étape 5, où un nombre peut être un numéro de générique ou de menu.

| # | Stratégie | Exemples | Saison | Épisode |
|---|---|---|---|---|
| 1 | `SxxExx` (casse indifférente, séparateur `.`, `_`, `-` ou espace toléré) | `Frieren - S01E05`, `s2e11` | du nom | du nom |
| 2 | `NxEE` (casse indifférente, saison sur 1 ou 2 chiffres, épisode sur 2 ou 3) | `2x06`, `Genshiken 01X01` | du nom | du nom |
| 3 | `E\d+` seul, cherché **hors crochets et parenthèses** (une empreinte CRC `[E4E2B273]` contient « E4 ») | `Ah! My Goddess E12` | `S\d` isolé dans le nom (`Slime 300 S1`), sinon **mot spécial collé à un numéro** dans le nom → 0, sinon **dossier**, sinon 1 | du nom |
| 5 | Numéro seul (numérotation absolue acceptée) | voir ci-dessous | comme la stratégie 3 | voir ci-dessous |

**Règle « numéro seul », exactement** :
1. partir du nom de fichier **sans l'extension** ;
2. retirer les groupes entre crochets, parenthèses ou accolades (`[Kaerizaki-Fansub]`, `[VOSTFR]`, `(2019)`, `{…}`) ;
3. retirer les éléments techniques : résolutions (`1080p`, `720p`, `1920x1080`), codecs (`x264`, `x265`, `H264`, `HEVC`, `AVC`), profondeur (`10bits`, `8bit`), audio (`AAC`, `AAC2.0`, `AC3`, `FLAC`, `Opus`, `DTS`, `MP3`, `DDP5.1`, `5.1`), sources (`WEB-DL`, `BDRip`, `BluRay`) ;
4. **candidats** : les nombres de **2 à 4 chiffres** précédés d'un **espace** ou d'un **underscore** (donc aussi « ` - 04` ») et suivis de la fin du nom, d'un espace, d'un `_`, d'un `.`, d'un `-`, d'une parenthèse ou d'un crochet, ou d'un suffixe de version `v2` ;
5. l'épisode est le **dernier candidat** (zéros initiaux ignorés : `098` → 98) ;
6. aucun candidat → **non résolu** (rapport). Un nombre à 1 chiffre n'est jamais candidat (`Little Witch Academia 1`).

Avant cette règle, un motif de double épisode (`05-06`, `03-04`) ou un numéro décimal (`0.89`, `24.5`) envoie le fichier au rapport (§7.5).

| Nom de fichier | Candidats | Épisode |
|---|---|---|
| `[matheousse] Slime 300 S1 - 01 MULTi [BD 1080p AAC Opus] [DBB79FF9]` | 300, 01 | **1** (300 fait partie du titre) |
| `One Piece Kaï - 098 - Totto Land - 1080p.VOSTFR.x264 [Sacha]` | 098 (1080p et x264 retirés) | **98** |
| `[Kaerizaki-Fansub]_One_Piece_Fish_Man_Island_01_[VERSION_LIGHT][VOSTFR][FHD_1920x1080]` | 01 | **1** |
| `[Kaerizaki-Fansub]_One_Piece_1124_[VOSTFR][FHD_1080p][HEVC_x265][10Bit]` | 1124 | **1124** |
| `Naruto Shippuden Kai 113 - Hebi` | 113 | **113** |
| `[Erai-raws] Hataage Kemono Michi - 06 [1080p][HEVC][78DBC505]` | 06 | **6** |

⚠️ Limite connue : un nombre de 2 à 4 chiffres dans le titre de l'épisode, **après** le numéro (« `Titre - 05 - Les 100 jours` »), serait pris à tort. Le seuil global des tests mesure ce risque sur la vraie liste.

Sans indication de saison (stratégies 3 et 5, ni `S\d` dans le nom ni dossier de saison) : **saison 1**. Exemple : `One Piece/Saison 9/One Piece Kaï - 098 - …` → saison 9, épisode 98.

Si un nom contient plusieurs motifs, le **premier** l'emporte et la suite est ignorée : `11 Eyes - S01E13 (OAV S1E01)` → S1 E13.

### 7.3 Spéciaux (saison 0)
- Saison 0 = « Spéciaux » : `S00Exx`, `0xNN`, ou fichier numéroté (stratégies 3 et 5) dans un dossier `OAV`, `OVA`, `Special(s)`, `Spéciaux`, `Bonus`.
- Aussi (décision du 2026-09-30), pour les stratégies 3 et 5 **sans saison explicite dans le nom** (`SxxExx`, `NxEE`, « S1 ») : le mot `Bonus`, `OAV`, `OVA` ou `Special` **collé à un numéro** (`Bonus - 01`, `OVA 02`, `Special 1` : seulement des séparateurs entre le mot et le nombre), cherché dans le nom **privé du titre de l'animé** (dossier de premier niveau), envoie en saison 0 (`seasonSource = NAME_SPECIAL`). Il l'emporte sur le dossier de saison, puisque le nom fait foi. « `Special A - 05` » reste en saison 1 (le mot n'est pas collé à un nombre), tout comme « `OVA 2 Stories - 05` » dans le dossier `OVA 2 Stories`. Sur la vraie liste, la règle touche exactement 13 fichiers : les 12 bonus `[DragonMax]` de Fate Stay Night et `Blue Exorcist Bonus 01`.
- Un `S00Exx` rangé dans un dossier OAV / Bonus reste un **épisode de la saison Spéciaux**, jamais un extra.
- Un fichier qui porte un **numéro d'épisode reste un épisode**, même si son dossier ou son nom contient « OAV », « Bonus » ou « Extra » : `OAV/L'Attaque des Titans - S00E18 - Lost Girls…` est l'épisode 18 de la saison 0 ; `Sekai Seifuku S01E13 OVA …` est l'épisode 13 de la saison 1, `Fate⁄EXTRA Last Encore S01E01 …` l'épisode 1.

### 7.4 Extras (hors liste des épisodes)
Le test d'extras est l'**étape 4** : il ne s'applique qu'aux vidéos **sans numéro d'épisode** d'après `SxxExx`, `NxEE` et `E\d+`, et seulement **avant** la stratégie « numéro seul ». Un extra est reconnu par :
- un marqueur dans le nom (après retrait des crochets et des éléments techniques) : `NCOP`, `NCED`, `NC OP`, `NC ED` (+ numéro et version : `NCED4`, `NCOPv2`), `OP` / `ED` **en majuscules** (+ numéro : `OP01`, `ED 02`), `Opening`, `Ending`, `Creditless`, `Menu` (menus BD), `Trailer`, `Teaser`, `Preview`, `AMV`, `OST` ;
- ou un dossier dédié : `OST`, `Music`, `OP - ED…`, `NC`, `Extra(s)`, `Menus`, `Trailers`, `PV`.

`OP` et `ED` sont sensibles à la casse : « `Takt Op. Destiny - 11` » est l'épisode 11, pas un générique.

Dans « `Nyan Koi! Menu - 05` » ou « `Blend S NCED4` », 05 et 4 ne sont donc pas des numéros d'épisode. Les extras sont enregistrés (`media_file.kind = EXTRA`), comptés au rapport, mais n'apparaissent pas dans la liste des épisodes.

### 7.5 Cas signalés au rapport (correction manuelle)
- **Doublon d'épisode** (même animé, saison, épisode) : un seul fichier est lié à l'épisode, l'autre est signalé. Quel fichier garder (décision du 2026-09-30) : si l'épisode était **déjà lié lors d'un scan précédent**, son fichier reste (un rescan ne change jamais un épisode existant) ; sinon, au sein du scan, le fichier identifié de la façon **la plus fiable** : correction manuelle, puis `SxxExx` / `NxEE`, puis `E\d+`, puis numéro seul ; l'ordre alphabétique ne départage que deux fichiers de même fiabilité. Le choix de la meilleure version (720p / 1080p…) reste dans FUTURE.
- **Double épisode** (`03-04`, `S01E03-E04`) et **numéro décimal** (`E05.5`, `0.89`, `Épisode 24.5`) : pas d'interprétation automatique, signalés.
- **Désaccord dossier / fichier** : importé selon le fichier, signalé.
- **Non résolu** : aucune stratégie ne répond et aucun marqueur d'extra.

### 7.6 Rapport de scan
- **Résumé par catégorie** : vidéos vues, épisodes reconnus, extras, non résolus, doublons, doubles épisodes / décimaux, désaccords dossier/fichier, fichiers disparus, autres fichiers (par type, dont sous-titres externes).
- **Liste filtrable** (par catégorie et par animé) des fichiers signalés, avec la raison et le chemin relatif. Réservé à l'admin.
- **Doublons et désaccords** : animé, saison, épisode, et **origine du numéro de saison** (`NAME_SXXEXX`, `NAME_NXEE`, `NAME_S` = « S1 » isolé dans le nom, `FOLDER` = dossier de saison, `SPECIAL_FOLDER` = dossier OAV/Bonus, `DEFAULT` = saison 1 faute d'indication, `OVERRIDE`). Pour un doublon : fichier **écarté** (`relativePath`), fichier **conservé** (`keptRelativePath`) et l'origine de la saison de chacun, de quoi repérer un sous-dossier non reconnu comme saison sans ouvrir les fichiers. `FullSampleScanTest` exporte les doublons du scan complet dans `backend/target/full-sample-duplicates.tsv`.

### 7.7 Correction manuelle
L'admin peut associer un fichier à un animé (par son **titre**, existant ou non), une saison et un numéro d'épisode, ou le marquer comme extra / ignoré. Il désigne le fichier par son **id** (`mediaFileId`, fourni par le rapport), jamais par un chemin. La correction est **stockée en base** (table `media_file_override`, clé : `relative_path`) et appliquée **à la place du parser** à chaque scan : un rescan ne l'écrase jamais. Elle prend effet **au scan suivant** (quelques secondes). Si le fichier disparaît, la correction est conservée (elle resservira s'il réapparaît au même chemin). `IGNORE` retire le fichier de la bibliothèque sans le toucher sur le disque.

**Épisode déjà fourni par un autre fichier** (décision du 2026-10-02) : si l'épisode visé est déjà lié à un autre fichier disponible (lien actuel, sauf si la correction de ce fichier l'envoie ailleurs) ou visé par la correction d'un autre fichier, le `PUT` répond **409 `EPISODE_ALREADY_LINKED`** avec l'épisode, le ou les fichiers qui seraient déliés (`currentFiles`, avec `viaOverride`) et le fichier visé (`targetFile`), **sans rien modifier**. `?replace=true` (après confirmation dans l'interface) enregistre la correction et supprime la correction de l'autre fichier s'il en avait une. Au scan, une correction l'emporte sur un fichier sans correction, même déjà lié ; le fichier délié reste disponible et ressort au rapport (doublon, avec le fichier conservé, ou non résolu s'il perd sa correction), donc corrigeable. Annuler la correction rétablit l'ancien lien au scan suivant, sur le même épisode (même id).
Le scan ne dépend pas de l'ordre des fichiers : un lien est **périmé** quand le fichier lié ne réclame plus cet épisode (correction vers ailleurs, extra, ignoré) ; le fichier qui réclame l'épisode le reprend alors, qu'il passe avant ou après dans l'ordre alphabétique (`OverrideConflictTest`).

### 7.8 Sous-titres externes
Seuls 24 des 434 sous-titres externes ont le même nom de base qu'une vidéo ; beaucoup sont rangés dans des dossiers du type `sous-titres + police/` avec des polices. Le MVP **ne les associe pas** : il les compte. La lecture s'appuie sur les sous-titres **intégrés (muxés) dans la vidéo**. L'association externe → FUTURE.

### 7.9 Idempotence
- Clé d'un fichier : `relative_path`. Nouveau chemin → création ; chemin connu → mise à jour de `file_size`, `last_modified`, `last_seen_at`.
- Fichier connu absent du scan → `available=false`, `missing_since=now` (aucune suppression). S'il réapparaît → `available=true`, `missing_since=null`.
- Anime identifié par `normalized_title` (minuscules, espaces/ponctuation normalisés) ; saison par (anime, numéro) ; épisode par (saison, numéro).
- **Rebranchement** : si le `media_file` lié à un épisode est indisponible et qu'un nouveau fichier est reconnu pour le même (anime, saison, numéro) — fichier renommé ou remplacé —, l'épisode est rebranché sur le nouveau fichier ; l'ancien reste en base, indisponible. L'épisode garde son id (utile plus tard pour la progression).
- L'API de lecture n'expose que les épisodes dont le fichier est `available`.

### 7.10 Interface du parser
```java
interface FilenameParser {
    ParseResult parse(String relativePath);   // chemin relatif à /media, séparateur "/"
}
// ParseResult = Episode(animeTitle, season, episode, strategy, folderSeasonConflict)
//             | Extra(animeTitle, marker)
//             | Unresolved(animeTitle, problem, detail)   // NO_EPISODE_NUMBER, MULTI_EPISODE, DECIMAL_EPISODE, NO_ANIME_FOLDER
```
Le parser ne touche jamais au disque : il ne reçoit que des chaînes. C'est ce qui permet de le tester sur `library-sample.txt`.

### 7.11 Tests du parser (phase 3)
Sur `library-sample.txt`, **chaînes uniquement, aucun vrai fichier** (certains noms contiennent des caractères interdits sous Windows : `:`, `?`, `"`…) :
- **seuil global** : au moins **97 %** d'épisodes reconnus, calculé sur les vidéos hors extras (`épisodes / (vidéos − extras)`). Garde-fou contre un parser qui classerait tout en extra : extras **< 3 %** des vidéos (le prototype en trouve 2,0 %) ;
- **un test par piège**, avec le résultat attendu :

| Chemin (extrait de l'échantillon) | Attendu |
|---|---|
| `I've Been Killing Slimes for 300 Years…/Saison 1/[matheousse] Slime 300 S1 - 01 MULTi [BD 1080p AAC Opus] [DBB79FF9].mkv` | S1 E1 (pas l'épisode 300) |
| `Genshiken/Saison 1/Genshiken 01X01.mkv` | S1 E1 (stratégie `NxEE`, X majuscule) |
| `AH! My Goddess/Saison 1/[Elecman] Ah! My Goddess E12 […].mkv` et `…/Saison 2/[Elecman] Ah My Goddess E12 […].mkv` | S1 E12 et S2 E12 (saison prise dans le dossier) ; **pas** un doublon |
| `Shingeki No Kyojin/OAV/L'Attaque des Titans - S00E18 - Lost Girls…mkv` | S0 E18 : un épisode de la saison Spéciaux, pas un extra (étape 1 avant l'étape 4) |
| `Devilman Crybaby/…` (deux fichiers par épisode) | un fichier gardé par épisode, les autres signalés « doublon » |
| `One Piece/Saison 7/[Kaerizaki-Fansub]_One_Piece_Fish_Man_Island_01_[VERSION_LIGHT][VOSTFR][FHD_1920x1080].mp4` | S7 E1 (nombre précédé d'un `_`) |
| `Naruto Shippuden/Naruto Shippuden Kai Intégrale VOSTFR/Naruto Shippuden Kai 113 - Hebi.mkv` | S1 E113 (nombre précédé d'un espace, sans « ` - ` » avant) |
| `One Piece/Saison 11/[Kaerizaki-Fansub]_One_Piece_1124_[VOSTFR][FHD_1080p][HEVC_x265][10Bit].mkv` | S11 E1124 (4 chiffres ; 1080 et x265 ignorés) |
| `One Piece/Saison 9/One Piece Kaï - 098 - Totto Land - 1080p.VOSTFR.x264 [Sacha].mp4` | S9 E98 (pas 264, pas 1080) |
| `Takt.OP Destiny/[Erai-raws] Takt Op. Destiny - 11 […].mkv` | E11 : épisode (« Op. » n'est pas un marqueur d'extra) |
| `Isekai Quartet/S2/NC/[Natsumi no Sekai] Isekai Quartet S2 - NCOP VOSTFR [BD 1080p AAC].mkv`, `Blend S/Extras/Blend S NCED4 […].mkv`, `Nyan Koi!/EXTRA/Nyan Koi! Menu - 05 (…).mkv` | extras (les numéros de générique ou de menu ne sont pas des épisodes) |
| `Little Witch/Little Witch Academia 1.mp4` | non résolu (1 seul chiffre) |
| `Macross Delta/[Lumen] Macross Delta 0.89.mkv` | signalé « numéro décimal » |

**Résultats** sur l'échantillon complet : 99,86 % d'épisodes reconnus hors extras (27 643 / 27 682), 2,0 % d'extras. Scan complet des 32 940 chemins recréés en fichiers vides (`FullSampleScanTest`) : 27 591 épisodes, 572 extras, 43 doublons (après la règle « Bonus/OVA collé à un numéro » ; 52 avant), 116 désaccords dossier/fichier, 26 non résolus, 8 doubles épisodes, 5 numéros décimaux, 1 316 animés visibles ; rescan identique, sans rien ajouter ni marquer disparu. (Avant la correction du « E » dans les empreintes CRC : 8 génériques pris pour des épisodes, 54 doublons.)

## 8. API (étape 1)

| Méthode | Route | Accès |
|---|---|---|
| GET | `/api/status` | public (`{"status":"UP"}`, vérifie la chaîne navigateur → nginx → backend) |
| POST | `/api/auth/login` | public (rate limited) |
| POST | `/api/auth/refresh` | cookie refresh |
| POST | `/api/auth/logout` | cookie refresh |
| GET | `/api/me` | authentifié (utile au front pour le rôle) |
| GET | `/api/anime?sort=recent\|title&q=&page=&size=` | authentifié (paginé : `{total, page, size, items}`) |
| GET | `/api/anime/{id}` | authentifié |
| GET | `/api/anime/{id}/seasons` | authentifié |
| GET | `/api/seasons/{id}/episodes` | authentifié |
| GET | `/api/episodes/{id}` | authentifié |
| GET | `/api/episodes/{id}/stream-url` | authentifié (URL de lecture signée, §6) |
| GET / HEAD | `/api/stream/{mediaFileId}?u=&exp=&sig=` | URL signée, sans en-tête (§6) |
| PUT | `/api/episodes/{id}/progress` | authentifié (§6.3) |
| GET | `/api/me/progress[?animeId=]`, `/api/me/continue-watching[?limit=]` | authentifié (§6.3) |
| POST | `/api/admin/library/scan` | ADMIN |
| GET | `/api/admin/library/scan-report` | ADMIN (résumé par catégorie du dernier scan, `failureCode` si échec) |
| GET | `/api/admin/library/scans?page=&size=` | ADMIN (historique des scans, du plus récent au plus ancien) |
| GET | `/api/admin/library/issues?category=&anime=&scanId=&page=&size=` | ADMIN (liste filtrable et paginée du rapport, avec `mediaFileId` et chemin relatif) |
| PUT / DELETE | `/api/admin/library/files/{mediaFileId}/override[?replace=true]` | ADMIN (correction manuelle d'un fichier, §7.7 ; 409 si l'épisode est déjà fourni par un autre fichier) |
| GET | `/api/admin/library/overrides` | ADMIN (liste des corrections) |
| GET | `/api/admin/metadata/summary`, `/api/admin/metadata?status=&q=` | ADMIN (métadonnées, §15.4) |
| GET | `/api/admin/anime/{id}/metadata/preview?providerId=` | ADMIN (§15.4) |
| PUT / DELETE | `/api/admin/anime/{id}/metadata[?replace=true]` | ADMIN (appariement manuel verrouillé, §15.4) |
| POST | `/api/admin/metadata/requeue?status=` | ADMIN (§15.4) |
| GET / POST | `/api/admin/users` | ADMIN |
| PATCH | `/api/admin/users/{id}` | ADMIN (activer/désactiver, rôle, mot de passe) |

Lecture : seuls les épisodes dont le fichier est disponible sont visibles (sinon 404) ; `/api/anime?sort=title|recent` (recent = dernier fichier ajouté) ; saisons dans l'ordre 1, 2… puis « Spéciaux » (saison 0) ; aucun chemin de fichier dans les réponses. Liste des animés paginée (60 par page par défaut, 200 au plus) avec recherche `q` dans le titre, insensible à la casse et aux accents (extension PostgreSQL `unaccent`, migration V5). Échec d'un scan : `failureCode` = `MEDIA_ROOT_UNAVAILABLE`, `MASS_REMOVAL` (relancer avec `confirmMassRemoval=true` si c'est voulu), `INTERRUPTED` ou `INTERNAL_ERROR`. Connexion bloquée par l'anti brute force : 429 `TOO_MANY_ATTEMPTS`, le message donne le délai en minutes. Un admin ne peut pas se désactiver ni retirer son propre rôle ADMIN (évite de se verrouiller dehors).
OpenAPI : `/q/openapi`, Swagger UI sur `/q/swagger-ui`, **actif en dev uniquement** par défaut (`SWAGGER_ENABLED`). nginx relaie ces deux chemins (avec une CSP assouplie pour la page Swagger) : `SWAGGER_ENABLED=true` suffit pour s'en servir sur le NAS. Le health check `/q/health` reste interne (healthcheck Docker).

## 9. Front Angular

- Angular 22 (dernière stable en phase 1, Node.js 24 LTS), **standalone components**, signals pour l'état local, `HttpClient`, Router avec lazy loading par page. SCSS avec variables CSS (thème sombre unique).
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
- Accessibilité / navigation clavier : `:focus-visible` très marqué, cibles ≥ 48 px, ordre de tabulation logique, liens et boutons natifs (pas de `div` cliquables), modales en `<dialog>` natif (focus piégé, Échap).
- Session (phase 4) : token d'accès en mémoire (`AuthService`), rétabli au démarrage par `/api/auth/refresh` (cookie HttpOnly) avant la première navigation (`provideAppInitializer`) : F5 ne déconnecte pas. L'interceptor ajoute le Bearer et, sur un 401, partage un seul refresh entre toutes les requêtes en échec puis les rejoue une fois ; refresh refusé → `/login?returnUrl=…` (chemins internes uniquement). Gardes `authGuard`, `adminGuard`, `guestGuard` ; l'API reste la vraie protection (`@RolesAllowed`).
- Design tokens : couleurs, espacements, typographie, rayons, ombres en variables CSS dans `styles.scss`, avec les classes de base (boutons, champs, tableaux, alertes) ; les composants n'utilisent que ces variables.
- État des listes dans l'URL (`?q=&tri=&page=`, `?categorie=&anime=` pour le rapport) : F5, retour arrière et liens partagés retrouvent la même vue. Pagination côté serveur.
- Les chemins de fichiers n'apparaissent que dans l'administration (rapport, corrections), relatifs à la racine de la bibliothèque ; l'API de lecture n'en renvoie jamais.

## 10. Docker / déploiement

- `backend` : build multi-étapes (Maven + JDK 21 → JRE 21), user non-root, `JAVA_OPTS=-Xmx512m`.
- `web` : build multi-étapes (Node → `nginx:alpine`), config nginx : fichiers statiques + fallback SPA vers `index.html` + `proxy_pass /api` + `real_ip` (voir §5.4.1) + log sans query string sur `/api/stream/`.
- `postgres` : `postgres:16-alpine`, volume nommé, non publié, healthcheck ; le backend attend qu'il soit sain.
- Montage média : `${MEDIA_PATH}:/media:ro`.
- ⚠️ **Permissions Synology** : le processus du conteneur backend doit pouvoir **lire** le dossier du propriétaire du NAS (ACL DSM). On expose `PUID`/`PGID` pour faire tourner le backend avec un utilisateur qui a le droit de lecture ; à documenter dans le README.
- Images construites localement ou sur le NAS (`docker compose build`) ; pas de registre à cette étape.

### Variables d'environnement (`.env.example`)
`MEDIA_PATH`, `WEB_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`, `STREAM_SIGNING_SECRET`, `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD`, `PUBLIC_URL`, `CORS_ORIGINS`, `SWAGGER_ENABLED`, `PUID`, `PGID`, `DOCKER_SUBNET`, `TRUSTED_PROXY_IPS`, `REFRESH_REUSE_GRACE_SECONDS`, `JAVA_OPTS`, `DEV_SPIKE_STREAM_ENABLED`, `DEV_MEDIA_PATH` et `COOKIE_SECURE` (dev uniquement).

## 11. Sécurité — récapitulatif
- Médias : lecture seule (montage `:ro` **et** aucune API d'écriture) ; accès uniquement par ID ; chemin résolu vérifié avec `toRealPath().startsWith(mediaRoot)` → test de path traversal (`../`, encodages, liens symboliques).
- Secrets uniquement via variables d'environnement ; démarrage refusé en prod si `JWT_SECRET`/`STREAM_SIGNING_SECRET` absents, trop courts (< 32 caractères) ou identiques, ou si `PUBLIC_URL` est absent ou n'est pas une origine exacte. Les messages d'erreur ne contiennent jamais les secrets.
- Logs : même en DEBUG, les loggers Hibernate qui afficheraient le contenu des entités ou les paramètres SQL (hash de mot de passe, hash de refresh token) restent en INFO (`application.properties`) ; vérifié par `LogLeakTest`.
- Logs : jamais de mot de passe, de token, de cookie ni de query string de streaming.
- Validation des entrées (Bean Validation sur tous les DTO).
- Headers de sécurité posés par nginx : `Content-Security-Policy` stricte, `X-Content-Type-Options`, `Referrer-Policy: same-origin`, `X-Frame-Options: DENY`.

## 12. Tests
- Backend : JUnit 5 + RestAssured + `@QuarkusTest`. PostgreSQL de test via **Quarkus Dev Services** (Testcontainers) → nécessite Docker Desktop lancé sur la machine de dev. Pas de H2 (comportement différent de PostgreSQL).
  Quarkus 3.20.0 embarque Testcontainers 1.20.6, qui ne sait pas parler à Docker Engine 29+ (« client version 1.32 is too old ») : le `pom.xml` force Testcontainers 1.21.4, à retirer quand Quarkus fournira une version plus récente.
- Tests obligatoires : Range Requests, path traversal, auth / permissions / brute force (dont : l'admin reste connectable depuis une autre IP), tolérance de rotation, chaîne d'IP, parsing sur `library-sample.txt` (seuil ≥ 97 % + un test par piège, §7.11), idempotence du rescan (dont : /media vide, scan orphelin, rebranchement d'épisode, correction manuelle conservée).
- Front : quelques tests unitaires ciblés (AuthService, interceptor, guards). Pas d'e2e à cette étape.

## 13. Décisions validées (2026-09-29)
1. `relative_path` au lieu de `absolutePath` (§4.2).
2. JWT en HS256 avec secret en variable d'environnement (§5.2).
3. Streaming par URL signée, durée 6 h, liée à l'utilisateur (§6).
4. Spike vidéo (phase 0) avant le socle.
5. Ajustements : brute force par (IP, username) + IP seule (§5.4), tolérance de rotation 20 s (§5.1), chaîne d'IP testée (§5.4.1), garde-fous du scan (§7.1), rebranchement d'épisode (§7.2).

## 14. Décisions sur la bibliothèque (2026-09-30, d'après la vraie bibliothèque)
1. Le nom du fichier fait foi ; le dossier de saison sert de secours et de vérification, jamais de condition d'import (§7.2).
2. Ordre de parsing : `SxxExx`, `NxEE`, `E\d+`, puis test d'extras, puis numéro seul (dernier nombre de 2 à 4 chiffres précédé d'un espace ou d'un `_`, éléments techniques ignorés) (§7.2, précisé le 2026-09-30).
3. Saison 0 = Spéciaux ; un numéro d'épisode l'emporte sur les mots OAV / Bonus / Extra (§7.3).
4. Extras = vidéos sans numéro d'épisode, exclues de la liste des épisodes (§7.4).
5. Doublons, doubles épisodes, décimaux et désaccords : rapport + correction manuelle, stockée en base et jamais écrasée (§7.5–7.7).
6. Sous-titres externes comptés, pas associés (§7.8).
7. Scan par lots, ffprobe hors du scan (§7.1).
8. Tests du parser sur chaînes, seuil ≥ 97 % (§7.11).

## 15. Métadonnées (phase 6)

### 15.1 Fournisseur
- Abstraction `MetadataProvider` (`search(titre)`, `byId(id)`, `synopsisLanguage()`). Premier fournisseur : **AniList** (API GraphQL publique, sans clé, `https://graphql.anilist.co`). Chaque fiche enregistre son fournisseur (`anime.metadata_provider`) et la langue de son synopsis (`anime.synopsis_language`) : un second fournisseur (ex. TMDB pour des synopsis en français) pourra s'ajouter sans migration de données.
- Ce que fournit AniList : titres **romaji** (`Sousou no Frieren`), **anglais** quand il existe (`Frieren: Beyond Journey's End`), **natif** (japonais) et **synonymes** (toutes langues, parfois français) ; format (TV, MOVIE, OVA, ONA, SPECIAL…), nombre d'épisodes, année (`seasonYear`, sinon année de début) ; **synopsis en anglais uniquement** (un peu de HTML, nettoyé en texte brut) ; affiches (`coverImage.large` ≈ 230 px de large pour la grille, `extraLarge` ≈ 460 px pour la fiche) hébergées sur `s4.anilist.co` ; page de la fiche (`siteUrl`, pour l'attribution).
- Ce qui est recopié dans `anime` : `alternative_title` (anglais, sinon romaji, s'il diffère du nom du dossier), `synopsis`, `synopsis_language`, `poster_url`, `poster_large_url`, `year`, `metadata_provider`, `metadata_provider_id`, `metadata_url`. **Le titre de l'animé reste le nom du dossier** (identité de l'animé pour le scan).
- Limite de débit AniList : 90 requêtes/min en temps normal, **30/min** actuellement (« degraded state », vérifié le 2026-10-03 : `X-RateLimit-Limit: 30`). Dépassement : 429 + `Retry-After` (et une minute de blocage). Maintenance : 403. Le client espace ses appels de **2,5 s** (24/min), respecte `Retry-After` et `X-RateLimit-Remaining: 0`, et traite 403 / 5xx / réseau comme « indisponible, réessayer plus tard ».

### 15.2 Appariement (`TitleMatcher`, fonctions pures)
1. Nom du dossier nettoyé : à partir de la première mention technique (`S01`, `VOSTFR`, `1080p`, `WEB`, `x264`…) le reste est ignoré ; `(2019)` ou `(2013-2016)` donne l'année ; un titre entre parenthèses devient une variante (`Rumbling Hearts (Kimi ga Nozomu Eien)`), une parenthèse technique (`(CR)`, `(BD 1080p)`) est retirée.
2. Recherche AniList sur le titre principal (10 résultats). Si elle ne suffit pas, recherches de repli, dans l'ordre : le même titre en romanisation ASCII (la recherche AniList ne trouve rien pour `Chûnibyô` ou `Kyō`, mais trouve `Chuunibyou`, `Kyou`), le titre entre parenthèses, la partie avant ` - ` (`Gotoubun no Hanayome - Quintuplets`). Les candidats s'additionnent.
3. Similarité de titre (0 à 1) : meilleur couple (variante du dossier, titre romaji / anglais / synonyme du candidat), après normalisation : accents, ponctuation, casse, `×` → `x`, `⁄` → espace, variantes de romanisation (`ō` / `ou` / `oo`, lettres doublées, particules `wo` → `o` et `ha` → `wa`). Article en tête ignoré (`The Great Cleric` = `Great Cleric`). Distance de Levenshtein sur la chaîne entière, sur les mots triés et sur la chaîne sans espaces (`Ao Ashi` = `Aoashi`, `11 Eyes` = `11eyes`) ; on garde la meilleure. Un titre de dossier d'au moins 4 lettres qui est le **début** du titre du candidat (`Frieren` → `Frieren: Beyond Journey's End`) vaut 0,80 à 0,90 selon la part du titre couverte : au mieux « douteux », jamais « apparié » sur ce seul indice.
4. Départages, ajoutés au score de classement seulement : année du dossier égale (+0,05) ou éloignée de plus d'un an (−0,10), nombre d'épisodes local à ±10 % (+0,02), format TV (+0,01).
5. Décision :
   - **non apparié** si la similarité du meilleur est < **0,80** (`LOW_SCORE`), s'il n'y a aucun résultat (`NO_RESULT`), ou si deux fiches au titre suffisant sont indiscernables (écart < 0,01) et que la ponctuation ne les départage pas non plus (`AMBIGUOUS` : remake homonyme sans année, `Captain Tsubasa` 1983 / 2018). Rien n'est recopié ; les 5 meilleurs candidats sont gardés pour l'admin.
   - ex aequo départagés par la ponctuation, mentions `(2011)` / `(TV)` mises à part : les suites ne diffèrent parfois que par elle (`Gochuumon wa Usagi desu ka?` / `…ka??`, `Dog Days` / `Dog Days'`) → la fiche la plus proche, **douteuse** ;
   - **apparié** si la similarité est ≥ **0,92** et que le suivant est nettement derrière (≥ 0,05, ou correspondance exacte face à des titres non exacts) ;
   - **douteux** sinon : la fiche est appliquée mais signalée à l'admin (`CLOSE_CANDIDATE` : un autre candidat très proche ; `BELOW_CONFIDENT` : titre seulement assez proche).
   Cas typiques : saisons séparées sur AniList (`Shingeki no Kyojin`, `… Season 2`) → la première saison, au titre exact ; remakes homonymes (`Hunter x Hunter` 1999 / 2011) → non apparié sans indice, apparié avec `(2011)` dans le nom du dossier, douteux si seul le nombre d'épisodes départage.

### 15.3 Tâche de fond (`MetadataWorker`, `MetadataService`)
- Un fil dédié, séparé du scan ; la bibliothèque, le scan et la lecture n'en dépendent jamais (sans fiche : visuel de remplacement, pas de synopsis).
- Un animé à la fois, un appel par animé (deux s'il a une variante entre parenthèses non concluante). **1 316 animés ≈ 55 min** au premier lancement (2,5 s par appel), puis seulement les nouveaux animés.
- État en base, table `anime_metadata_match` (V7) : pas de ligne = à faire ; `PENDING` (à refaire), `MATCHED`, `DOUBTFUL`, `UNMATCHED`, `MANUAL`. **Idempotente** (un animé décidé n'est jamais redemandé) et **reprenable** (après un arrêt, elle repart sur ce qui reste).
- Fournisseur indisponible ou limite de débit : l'animé reste « à faire », la tâche se met en pause (`Retry-After`, sinon 5 min) puis reprend. Erreur propre à un animé : nouvel essai avec délai croissant, abandon en « non apparié » (`ERROR`) après 5 échecs.
- Réveil à la fin de chaque scan ; sinon vérification toutes les 10 min. `METADATA_ENABLED=false` la désactive.
- Une fiche **verrouillée** (correction manuelle, `locked`) n'est jamais modifiée par la tâche ni par un rescan (garde dans la requête d'écriture elle-même).
- Tests : `TitleMatcherTest` (sans réseau), `AniListProviderTest` et `MetadataTest` contre un faux serveur AniList (`FakeAniList`, JDK `HttpServer`) : aucun appel réseau réel.

### 15.4 Corrections par l'admin
- `GET /api/admin/metadata/summary` : nombre d'animés par statut, tâche active ou non, pause en cours (limite de débit, panne) et temps restant estimé.
- `GET /api/admin/metadata?status=&q=&page=&size=` : liste paginée (filtre par statut et par titre), avec pour chaque animé le statut, la raison (`NO_RESULT`, `LOW_SCORE`, `AMBIGUOUS`, `CLOSE_CANDIDATE`, `BELOW_CONFIDENT`, `ERROR`), le score, la fiche appliquée et les **5 meilleurs candidats** (titre, année, format, épisodes, affiche, lien AniList) : l'admin choisit sans refaire de recherche.
- `GET /api/admin/anime/{id}/metadata/preview?providerId=` : fiche AniList par identifiant (le nombre dans `anilist.co/anime/<id>`), avant de l'appliquer.
- `PUT /api/admin/anime/{id}/metadata[?replace=true]` `{"providerId":"11061"}` (ou `null` = « aucune fiche ») : appariement **manuel, verrouillé** (`MANUAL`, `locked`), jamais écrasé par la tâche automatique, une relance ni un rescan. **Même principe que les corrections de fichiers** : 409 `METADATA_CONFLICT` sans rien modifier si l'animé a déjà une autre fiche (`current` / `proposed`) ou si la fiche sert déjà à un autre animé (`otherAnime`) ; `replace=true` confirme. AniList indisponible : 503 `METADATA_PROVIDER_UNAVAILABLE`, rien n'est modifié.
- `DELETE /api/admin/anime/{id}/metadata` : retire le verrou, la tâche refait l'appariement automatique.
- `POST /api/admin/metadata/requeue?status=UNMATCHED|DOUBTFUL|MATCHED` : relance l'appariement automatique de ces animés (les verrouillés ne bougent pas), par exemple après une amélioration de l'algorithme.

### 15.5 Mesure sur la vraie bibliothèque (2026-10-02, AniList réel)
Les 1 316 dossiers d'animés de `library-sample.txt` (bibliothèque factice), contre l'API AniList réelle, avec la version livrée :

| Statut | Animés | Part |
|---|---|---|
| Apparié | 1 108 | 84,2 % |
| Douteux (appliqué, à vérifier) | 139 | 10,6 % |
| Non apparié | 69 | 5,2 % (49 sans résultat, 15 titres trop différents, 5 ambigus) |

Avec une fiche (affiche, synopsis, année) : 1 247 animés (94,8 %). Durée du premier passage : environ 1 h (une seule limite de débit 429 rencontrée, gérée). Sur un échantillon relu à la main, les appariés sont justes ; les douteux le sont en majorité, avec quelques erreurs (ex. `Granblue Fantasy` → une fiche dont un synonyme commence pareil) : c'est leur rôle d'être revus. Non appariés typiques : titre français (`Le Seigneur des Yôkai`), coupure différente (`To Aru` / `Toaru`, `Summertime` / `Summer Time`), fiche marquée « adulte » sur AniList (`Yosuga no Sora`, exclue de la recherche automatique pour ne jamais afficher une affiche pour adultes par erreur), raccourci (`Iruma`). Tous se corrigent dans l'admin par identifiant AniList.

## 16. Synopsis en français : étude (étape 6.1, 2026-10-03)

Besoin : synopsis (et si possible titre) en français, via un second `MetadataProvider`, AniList restant la base (affiche, année, appariement) et le repli anglais.

### 16.1 Options comparées (sources officielles lues le 2026-10-03)

| | **TMDB** | TheTVDB (v4) | Kitsu | AniDB |
|---|---|---|---|---|
| Synopsis français | Oui : `language=fr-FR` sur les séries, saisons et films (« most of our metadata endpoints support translated data ») ; couverture des animés **à mesurer** (traductions communautaires, souvent vides pour les titres de niche) | Traductions par langue (couverture non vérifiée) | **Non** : synopsis en anglais seulement, titres sans français (vérifié sur l'API) | Descriptions multilingues annoncées, en pratique surtout anglais |
| Modèle | Une **série** avec ses saisons (une fiche AniList par saison) ; films à part ; `original_name` = titre japonais | Comme TMDB (séries / saisons) | Une fiche par saison (comme AniList) | Une fiche par saison |
| Clé | Oui, gratuite : **jeton de lecture** (Bearer, méthode recommandée) ou clé `api_key` | Oui | Non | Client à enregistrer |
| Usage non commercial | **Gratuit pour un usage non commercial, avec attribution** (« free to use for non-commercial purposes as long as you attribute TMDB ») | Gratuit sous 50 k$/an de revenus, **attribution + lien obligatoires** | — | — |
| Attribution | Mention « This product uses the TMDB API but is not endorsed or certified by TMDB » + logo officiel TMDB, **moins visible** que le logo de l'application, dans une page « À propos / Crédits » ; logo non modifié ; nommer « TMDB » ou « The Movie Database » | « Metadata provided by TheTVDB » avec lien direct vers thetvdb.com, visible des utilisateurs | — | — |
| Images | URL `image.tmdb.org/t/p/<taille>/<fichier>` | **La licence de l'API n'autorise pas l'usage des images** (« it is your responsibility to secure … all rights ») | — | — |
| Conservation | **6 mois au plus** pour toute information obtenue de TMDB, images comprises (conditions de l'API, §1.C : « Cache, for longer than 6 months, any information obtained through or from TMDB ») ; en cas de fin de licence, tout effacer, cache compris (§1.D) | Rien de précis dans les CGU | — | Obligation de cache local ; redemander la même fiche le même jour peut valoir un bannissement |
| Débit | ~40 requêtes/s (« somewhere in the 40 requests per second range »), 429 au-delà | « Pas d'appels excessifs », sans chiffre | Non documenté | **1 page / 2 s**, bannissement en cas d'abus |

Pistes écartées : sites français (Nautiljon, Anime-Sanctuary) sans API publique (et leur recopie serait du scraping) ; Crunchyroll / ADN sans API publique ; Kitsu et AniDB sans français.
Piste notée pour plus tard : la liste de correspondances **Kometa Anime-IDs** (licence MIT, régénérée chaque jour) donne pour un animé AniList l'identifiant de série / film TMDB et TVDB : un pont d'identifiants qui éviterait la recherche par titre pour une bonne partie de la bibliothèque. Dépendance supplémentaire (un fichier JSON à télécharger) : à n'ajouter que si la recherche par titre déçoit.

### 16.2 Recommandation : TMDB
- Seule option qui coche à la fois **français**, **conditions compatibles** (usage privé non commercial avec attribution), **débit confortable**, **images utilisables** (utile pour 6.2) et API documentée. TheTVDB est la seule alternative sérieuse, mais sa licence n'autorise pas les images et sa couverture française n'est pas vérifiable sans clé.
- **Clé** : `TMDB_READ_TOKEN` (Bearer, recommandé par TMDB) ou `TMDB_API_KEY`, lues côté backend uniquement, jamais renvoyées au client ni écrites dans les logs (même traitement que les secrets : `toString` masqué, en-tête Authorization jamais journalisé, test `LogLeakTest`). **Sans clé : démarrage normal**, le fournisseur TMDB est simplement inactif et le synopsis anglais d'AniList reste affiché.
- **Appariement prévu** : repartir de la fiche AniList déjà trouvée (titre romaji, anglais, **natif japonais** comparé à `original_name` de TMDB, année de début, format). AniList TV / ONA → `search/tv` ; MOVIE → `search/movie` ; `include_adult=false`. Même similarité et mêmes seuils qu'en §15.2, même état « non apparié », même correction manuelle verrouillée (identifiant TMDB) avec confirmation.
- **Saisons** : notre animé est un dossier, souvent plusieurs saisons ; on prend le **synopsis de la série** TMDB. Si la fiche AniList retenue est une suite (« Season 2 »), on cherche la série de base et, si la saison TMDB correspondante a un synopsis français (`tv/{id}/season/{n}`), on peut le proposer par saison sur la fiche ; sinon celui de la série.
- **Ce qui est enregistré par champ** : fournisseur, langue, date de récupération (synopsis FR TMDB, sinon EN AniList ; titre FR TMDB s'il existe). Priorité : correction manuelle > TMDB FR > AniList EN.
- **Rafraîchissement** : chaque fiche TMDB est redemandée avant la durée de conservation maximale autorisée par TMDB (paramétrable ; valeur à fixer d'après le texte officiel, voir 16.3). 1 300 fiches à 2 appels chacune = quelques minutes, sans souci de débit.
- **Attribution** : page « À propos » (mention exigée + logo TMDB officiel, plus petit que le nom de l'application) et mention discrète « Synopsis : TMDB » sous le synopsis.
- **Avant de tout construire**, mesurer la couverture réelle : un essai à blanc sur les 1 247 animés appariés, avec ta clé, donne le pourcentage d'animés qui auraient un synopsis français.

### 16.3 Conditions d'utilisation de l'API TMDB (texte du 20 octobre 2023, fourni le 2026-10-03)
Ce qui nous concerne, et ce que le projet en fait :
- **Usage non commercial uniquement** (§2) : serveur privé, gratuit, sans publicité : conforme. Le jour où l'usage deviendrait payant, il faudrait un accord commercial.
- **Conservation : 6 mois au plus** (§1.C), données **et images** : chaque fiche TMDB (et en 6.2 chaque affiche venue de TMDB) porte sa date de récupération et est **rafraîchie avant 6 mois** (marge : rafraîchissement à 5 mois) ; une donnée qui n'a pas pu être rafraîchie à temps est **effacée** à 6 mois (repli AniList anglais).
- **Fin de licence** (§1.D) : tout effacer, cache compris → une action d'admin (et la suppression de la clé) purge toutes les données et images TMDB.
- **Pas d'œuvre dérivée** (§1.C « Make derivatives … of TMDB Content ») : synopsis affiché tel quel (pas de résumé, troncature ni traduction automatique), affiches non retouchées.
- **Attribution** (§3) : logo TMDB **moins visible** que le nom de l'application, et la mention exacte, placée **bien en vue** dans l'application : « This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB. » → pied de page de l'application (toutes les pages) et page « À propos », plus la source « TMDB » sous chaque synopsis concerné.
- **Débit** (§1.C) : pas de consommation excessive → espacement des appels même si la limite technique (~40/s) est large, et pas de rafraîchissement inutile.
- **Pas d'usage IA** de l'API ni des données (§1.C, §2) : sans objet ici.

### 16.4 Point restant avant d'implémenter (étude)
1. **Clé TMDB** : à créer sur ton compte TMDB (Paramètres → API, usage personnel / non commercial), puis à mettre dans `.env` (`TMDB_READ_TOKEN`). Elle n'est nécessaire ni pour l'implémentation ni pour les tests (faux serveur), seulement pour la mesure de couverture et l'usage réel.

### 16.5 Implémentation (étape 6.1)
- **Table `anime_tmdb`** (V8), une ligne par animé : statut (mêmes valeurs qu'AniList), `tmdb_type` + `tmdb_id`, score, raison, candidats (pour l'admin), verrou, et les **champs récupérés avec leur langue et leur date** (`language`, `title`, `synopsis`, `poster_path`, `fetched_at`). Les champs AniList de `anime` ont désormais aussi leur date (`metadata_fetched_at`), à côté du fournisseur (`metadata_provider`) et de la langue (`synopsis_language`).
- **Tâche de fond** `TmdbWorker` (même boucle que la tâche AniList : `BackgroundLoop`), inactive sans clé. À chaque pas : (1) effacer ce qui a plus de 6 mois ; (2) apparier un animé nouveau, **une fois AniList passé** (ses titres rendent la recherche sûre) ; (3) sinon, redemander une fiche de plus de 5 mois (sans refaire l'appariement, même verrouillée : une correction manuelle fixe l'identifiant, pas le contenu). Appels espacés de 250 ms ; 429 → pause selon `Retry-After` ; 401 → pause d'une heure, message dans l'admin.
- **Recherche** : titres connus dans l'ordre japonais, romaji, anglais, autre titre, dossier ; mentions de saison retirées (« Season 2 », « 2nd Season », « Part 2 », « II »…), au plus 4 recherches par animé. `search/tv`, ou `search/movie` si AniList dit MOVIE (ou, sans fiche AniList, pour un dossier d'un seul fichier). `include_adult=false` et les résultats `adult` écartés.
- **Décision** : similarité de titre (contre `name` et `original_name`) et seuils d'AniList (§15.2) ; départage : genre Animation (+), même année (+), série commencée après notre saison (−), série plus ancienne que notre première saison (−, pas pour une suite). **MATCHED exige le genre Animation** : une adaptation en prises de vue réelles au même titre reste au mieux « douteuse ».
- **Saisons** : on prend le synopsis **de la série** TMDB ; le synopsis par saison est noté dans `docs/FUTURE.md`.
- **Affichage** (`GET /api/anime/{id}`) : synopsis français de TMDB s'il existe et a moins de 6 mois, sinon anglais d'AniList ; `synopsisSource`, `synopsisLanguage`, `frenchTitle` (titre TMDB s'il diffère du titre original), `tmdbUrl`. La fiche web le dit discrètement : « Synopsis en anglais (pas de traduction française) » et les liens des sources.
- **Clé** : `TMDB_READ_TOKEN` en en-tête `Authorization: Bearer` (ou `TMDB_API_KEY` en paramètre) ; jamais dans un message d'erreur, un log ni une réponse (test). Redirections HTTP refusées.
- **Attribution** : pied de page (mention exacte) et page publique « À propos » (`/a-propos`) avec le logo officiel en petit (fichier à déposer : `web/public/attribution/tmdb-logo.svg`, nom « TMDB » en texte à défaut).

### 16.6 Administration (onglet « Synopsis français »)
Animés sans synopsis français par défaut (non appariés, douteux, ou fiche TMDB sans traduction) ; correction par candidat, par adresse ou identifiant TMDB (`tv/209867`, `movie/372058`, avec prévisualisation), ou « aucune fiche TMDB ». Verrouillée ; remplacer une fiche existante → 409 `TMDB_CONFLICT` puis confirmation (`replace=true`). Relance des non appariés / douteux. **Purge** (`POST /api/admin/tmdb/purge?confirm=true`) : efface toutes les données TMDB, corrections comprises (fin de licence, §1.D) ; retirer aussi la clé, sinon la tâche recommence.

## 17. Affiches stockées sur le NAS (étape 6.2)

### 17.1 Stockage
- Dossier **`POSTERS_PATH`** (défaut `/data/posters` dans le conteneur), monté **en écriture** depuis `POSTERS_HOST_PATH` (`.env`). C'est le seul dossier où le backend écrit ; `/media` reste en lecture seule. Dossier absent ou non accessible en écriture : la tâche ne démarre pas (avertissement dans le journal et dans l'admin), les affiches restent distantes.
- Fichiers **nommés par leur empreinte** SHA-256, rangés par les 2 premiers caractères : `ab/ab12…64.jpg` (extension d'après le contenu réel : jpg, png, webp). Écriture dans un fichier temporaire puis déplacement atomique ; un fichier déjà présent n'est jamais réécrit. Deux animés avec la même image (deux saisons d'une série TMDB) partagent le fichier. Au démarrage de la tâche : ménage des fichiers temporaires laissés par une interruption et des fichiers que plus aucune ligne ne référence.
- Table **`anime_poster`** (V9), une ligne par animé : statut, fournisseur, URL source, identifiant public, chemin relatif, empreinte, type, taille, date de récupération, source en échec, essais.

### 17.2 Téléchargement (tâche de fond, séparée du scan)
- Source voulue : **affiche TMDB** (`image.tmdb.org/t/p/w500<poster_path>`) si la fiche TMDB a moins de 6 mois, **sinon AniList** (`extraLarge`). On ne télécharge que ce qui manque ou a changé de source ; une image déjà présente pour la même source est réutilisée sans appel réseau. État en base : reprise après redémarrage, idempotent.
- Contrôles : **hôtes autorisés** seulement (`image.tmdb.org`, `s4.anilist.co`), https, pas d'identifiants dans l'URL ; **aucune redirection suivie** ; `Content-Type` image/jpeg, png ou webp ; **octets magiques** cohérents avec ce type ; **5 Mo au plus** (en-tête et lecture plafonnée). Un refus (pas une image, trop grosse, 404, hôte, redirection) n'est plus retenté tant que la source ne change pas (ou « Retélécharger ») ; un échec passager (HTTP 5xx) est retenté avec un délai croissant, 5 fois ; serveur injoignable ou 429 : pause de la tâche.
- Débit : une image toutes les 0,5 s.
- **Conditions TMDB** : une affiche TMDB est retéléchargée après 5 mois et **effacée à 6** (ligne et fichier), comme quand sa fiche TMDB disparaît ; la purge TMDB (fin de licence) efface aussi toutes les affiches TMDB.

### 17.3 Service des affiches : identifiant aléatoire (décision)
- `GET /api/posters/{publicId}`, **sans authentification**, `publicId` = 128 bits aléatoires (32 caractères hexadécimaux), cherché en base. **Aucun chemin ne vient du client** ; même le chemin lu en base est vérifié (forme exacte `xx/<64 hex>.ext`, résolu à l'intérieur du dossier, fichier ordinaire) : test de path traversal.
- Pourquoi pas d'authentification ni d'URL signée : une balise `<img>` n'envoie pas l'en-tête `Authorization`. Une URL signée (comme le streaming, §6) changerait à chaque expiration et casserait le cache navigateur pour un gain faible : une affiche n'est pas une donnée sensible. Un identifiant aléatoire n'est connu que de qui a appelé l'API authentifiée.
- **Limites** : qui a obtenu une URL d'affiche (utilisateur, historique, journal d'un proxy) peut la recharger sans compte tant qu'elle existe ; elle ne révèle qu'une image publique. Les noms de fichiers (empreintes) ne sont jamais exposés : on ne peut pas tester « ce serveur a-t-il telle affiche » en calculant l'empreinte d'une image connue.
- L'identifiant change quand l'image change : `Cache-Control: private, max-age=30 jours, immutable`, `ETag` = empreinte. 30 jours restent sous la limite TMDB (retéléchargement à 5 mois + 30 jours < 6 mois).
- API : `posterUrl` / `posterLargeUrl` = **fichier local**, sinon **URL distante** (TMDB, puis AniList), sinon `null` (visuel de remplacement côté web). Fichier local disparu : URL distante, et la tâche le retélécharge.

### 17.4 CSP
`img-src 'self' data: https://image.tmdb.org https://s4.anilist.co` (au lieu de `https:`) : affiches locales servies par l'application, repli distant limité aux deux sources. Si AniList change d'hôte d'images, les affiches distantes concernées tombent sur le visuel de remplacement : il suffit d'ajouter l'hôte ici et dans `anime.posters.allowed-hosts`.

### 17.5 Administration (onglet « Affiches »)
Nombre d'affiches sur le NAS (TMDB / AniList), distantes, absentes, en échec ; place utilisée, estimation une fois tout téléchargé (taille moyenne × animés avec une affiche), espace libre du volume ; liste filtrable avec l'erreur ; « Retélécharger » par animé (oublie les échecs ; l'affiche actuelle reste servie en attendant).

## 18. Distribution (personnages et comédiens) : étude (étape 6.3, 2026-10-03)

Besoin : sur la fiche d'un animé, les personnages (nom, image, rôle principal / secondaire) et leurs comédiens de doublage (nom, image, langue : japonais par défaut, la langue reste dans le modèle pour les doubleurs français plus tard) ; une page comédien qui liste **les animés de la bibliothèque** où il joue, avec le personnage. L'identité d'un comédien doit être la même d'un animé à l'autre, sinon sa page est incomplète sans que personne le voie.

### 18.1 Mesure sur 25 animés de la vraie bibliothèque
Échantillon : 12 titres connus (One Piece, Naruto Shippuden, Bleach, Death Note, Frieren, L'Attaque des Titans, Demon Slayer, Jujutsu Kaisen, Spy x Family, Steins;Gate, Evangelion, Mob Psycho 100) et 13 tirés au hasard parmi les appariés (Chobits, Inukami!, Myself Yourself, Chaos;Head, Triage X, séries 2026…). Fiches AniList déjà appariées en phase 6 ; fiches TMDB trouvées pour les 25. AniList : `characters` (rôles MAIN / SUPPORTING / BACKGROUND, comédiens avec `languageV2`) ; TMDB : `tv/{id}/aggregate_credits`. Mesure TMDB faite sur le PC (le jeton n'en sort pas).

| | AniList | TMDB |
|---|---|---|
| Animés avec une distribution | 25 / 25 | 25 / 25 |
| Rôle principal / secondaire | **oui** (100 principaux, 826 secondaires, 224 figurants sur 1 150 personnages lus) | **non** (seulement un ordre et un nombre d'épisodes ; 39 % des comédiens n'ont qu'un épisode) |
| Doubleur japonais | **100 %** des rôles principaux, **99 %** des secondaires | tous les comédiens reconnus sont japonais, mais **aucune langue indiquée** (impossible d'y distinguer un doublage français) |
| Langue du comédien | **oui** (`Japanese`, `French`, `English`…) ; doubleurs français présents sur 13 / 25 (titres connus surtout) | non |
| Nom du comédien | romanisé (« Mayumi Tanaka ») **et** natif (« 田中真弓 ») : 99,8 % | **62 % des noms seulement en japonais** (35 % même parmi les 10 premiers crédités) ; un nom d'origine à côté dans 37 % des cas |
| Nom du personnage | propre (« Frieren ») | presque toujours avec « (voice) » ; 6 % vides ; quelques noms en chinois (Mekakucity Actors) |
| Photo du comédien | 99,9 % | 98 % |
| Image du personnage | 95 % | non (TMDB n'a pas de fiche personnage) |
| Saisons | une fiche **par saison** : distribution de la saison appariée seulement | une série : **toutes les saisons** (L'Attaque des Titans : 221 comédiens contre 77 sur la fiche AniList de la saison 1) |

Recoupement : 95 % des doubleurs japonais des rôles principaux d'AniList se retrouvent dans TMDB (110 / 116), 78 % de tous ceux des rôles principaux et secondaires (708 / 910), en comparant les noms romanisés **et** les noms japonais ; avec les seuls noms romanisés, l'écriture japonaise de TMDB ferait échouer la majorité des rapprochements.

21 % des dossiers de la bibliothèque (277 / 1 316) contiennent plusieurs saisons.

### 18.2 Conditions d'utilisation (lues le 2026-10-03)
- **AniList** (docs.anilist.co, « Terms of use ») : usage gratuit sous 150 $ de revenus par mois ; si « AniList » figure dans le nom de l'application, dire qu'elle n'est pas officielle (sans objet) ; **interdit : se servir de l'API comme sauvegarde ou stockage de données, et « l'accumulation ou la collecte massive » de données**. Aucune règle écrite sur la durée de conservation ni sur les images. Débit : 90 requêtes / min, **30 en ce moment** (mode dégradé), blocage d'IP en cas d'abus. Conséquence : ne récupérer **que** la distribution des animés de la bibliothèque, plafonnée, jamais la filmographie complète d'un comédien ; les images restent la propriété de leurs ayants droit, affichées telles quelles pour l'usage privé du groupe.
- **TMDB** (texte du 20 octobre 2023 fourni le 2026-10-03 ; la page officielle refuse les robots, pas relue en direct) : non commercial, attribution, **6 mois au plus** pour toute donnée et image (photos des comédiens comprises), purge en fin de licence, pas d'œuvre dérivée.

### 18.3 Deux conceptions
**(a) Source unique.** Une personne = un identifiant d'un seul fournisseur ; la page comédien est complète par construction pour les animés qui ont une distribution.

**(b) Deux sources, TMDB d'abord, AniList en repli.** Une personne = (fournisseur, identifiant), page comédien limitée à un fournisseur, fusion manuelle par l'admin. Problèmes mesurés : TMDB a une distribution pour 25 / 25, donc AniList ne servirait presque jamais de repli et la source réelle serait TMDB, **sans rôle principal / secondaire ni langue**, avec 62 % de noms illisibles pour qui ne lit pas le japonais, et des personnages « Frieren (voice) » sans image. Dès qu'un animé tombe en repli AniList, son comédien devient une **autre personne** : sa page est incomplète sans signe visible. Fusionner à la main des milliers de comédiens n'est pas réaliste ; un rapprochement automatique par nom japonais marche souvent (95 % sur les rôles principaux) mais pas toujours, et se tromperait en silence sur les homonymes. Les photos TMDB doivent en plus être renouvelées tous les 6 mois.

### 18.4 Recommandation : (a), source unique **AniList** pour la distribution
Contraire à la règle générale « TMDB d'abord » (synopsis, affiches), mais c'est la seule source qui donne à la fois rôle, langue, noms lisibles et natifs, image du personnage, et une identité de comédien stable (identifiant AniList « staff ») ; elle permettra d'ajouter les doubleurs français (déjà là pour les titres connus). TMDB n'est pas utilisé pour la distribution.

Limites :
- **Saisons** : une fiche AniList = une saison. Pour les 21 % de dossiers à plusieurs saisons, proposition : suivre la chaîne de suites d'AniList (relations `SEQUEL`, formats TV / ONA) jusqu'au nombre de saisons du dossier, au plus 6 ; les personnages déjà vus ne sont pas dupliqués. Sans cela, les personnages apparus après la saison 1 manquent (et le comédien n'a pas l'animé sur sa page).
- Animés sans appariement AniList (5 % non appariés, et les douteux non validés) : pas de distribution (signalés dans l'admin). Aucun nouvel appariement n'est fait par cette étape.
- Doublons de personnes chez AniList même : rares ; fusion manuelle notée dans `FUTURE.md`.
- Dépendance au débit d'AniList (partagé avec la tâche des métadonnées : même limiteur).

### 18.5 Plafonds, espace disque, durée
- **Rôles** : tous les principaux puis les secondaires par pertinence, **20 au plus** par fiche AniList (une seule page de requête ; moyenne mesurée 19,9 avec un plafond de 25) ; pas de figurants. **Un** doubleur japonais par rôle (le plus pertinent) ; la langue est stockée (doubleurs français plus tard, même requête).
- **Images** : personnage en taille `medium` (100 × 150, 21 Ko en moyenne, mesuré) ; comédien en `large` (230 × 345, 88 Ko en moyenne), réduite à l'affichage sur la fiche. 1 Mo au plus par image (refus au-delà). Même mécanisme que les affiches (§17).
- **Espace** (1 316 animés) : ~25 000 rôles × 21 Ko ≈ **0,5 Go** d'images de personnages ; ~4 000 à 7 000 comédiens distincts (351 pour l'échantillon de 25) × 88 Ko ≈ **0,35 à 0,6 Go**. Total **≈ 1 Go**. Variante économe (12 rôles, comédiens en `medium`) : ≈ 0,4 Go.
- **Durée du premier passage** : une requête AniList par fiche (personnages + comédiens dans la même réponse) → 1 316 requêtes, plus ~600 pour les suites, au rythme actuel (une requête toutes les 2,5 s, partagé avec les métadonnées) : **≈ 1 h 20**. Images (CDN d'AniList, hors limite de l'API mais sans abus) : ~30 000 fichiers à 2 par seconde → **≈ 4 h**, en tâche de fond.
- **Nouveaux animés** : la distribution ne peut pas être ajoutée à la requête existante de la phase 6, qui est une **recherche** renvoyant plusieurs candidats (on paierait la distribution de chaque candidat) : une requête de plus par animé apparié, sans importance au rythme des ajouts.
- **Rafraîchissement** : AniList n'impose rien ; redemander une distribution tous les 6 mois suffit (nouveaux personnages d'une série en cours). Le modèle garde fournisseur, langue et date de récupération : si TMDB servait un jour, ses règles (5 / 6 mois, purge) s'appliqueraient comme en §16.

### 18.6 Décisions à prendre avant d'implémenter
1. Source unique AniList (recommandé) ou deux sources ?
2. Suivre les suites AniList pour les dossiers à plusieurs saisons (+ ~600 requêtes) ?
3. Plafonds : 20 rôles, personnages `medium`, comédiens `large` (≈ 1 Go), ou la variante économe (≈ 0,4 Go) ?

