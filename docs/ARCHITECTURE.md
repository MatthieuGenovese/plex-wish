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
  (`Secure` désactivable en dev HTTP via `COOKIE_SECURE=false`.)
- **Rotation** à chaque `/refresh` : l'ancien est révoqué (`ROTATED`), un nouveau est émis.
- **Fenêtre de tolérance de 20 s** (configurable, `REFRESH_REUSE_GRACE_SECONDS`, 10–30 s) : si un token révoqué pour cause de rotation il y a moins de 20 s est représenté, c'est presque toujours une course légitime (deux onglets, double appel au démarrage, réseau mobile qui rejoue la requête). On renvoie alors **un nouvel access token sans émettre de nouveau refresh token ni toucher au cookie** : le navigateur a déjà reçu le bon cookie via la requête concurrente.
- **Réutilisation hors fenêtre** (ou d'un token révoqué pour une autre raison que la rotation) → vol probable : tous les refresh tokens de l'utilisateur sont révoqués (`REUSE_DETECTED`), réponse 401.
- Rechargement de page : Angular appelle `/api/auth/refresh` au démarrage (le cookie part tout seul) pour récupérer un access token.
- **Logout** : révoque le refresh token courant et efface le cookie.
- Désactiver un utilisateur révoque ses refresh tokens ; son access token reste valide au plus 15 min (acceptable ici).

Pourquoi pas tout en cookie de session ? Le couple « access en mémoire + refresh HttpOnly » protège contre le vol par XSS (le JS ne peut pas lire le refresh token) et reste utilisable plus tard par l'app Android (qui recevra le refresh token dans le corps de la réponse sur demande explicite, et le stockera chiffré — hors scope maintenant).

CSRF : le seul endpoint qui s'appuie sur le cookie est `/api/auth/*` ; `SameSite=Strict` + même origine suffisent. En complément, **toute requête d'écriture sur `/api`** (POST, PUT, PATCH, DELETE) dont le header `Origin` est présent doit venir de `PUBLIC_URL` (ou de `CORS_ORIGINS` en dev), sinon `403 ORIGIN_NOT_ALLOWED` (`auth/OriginCheck`). Sans header `Origin` (curl, future app Android), la requête passe : l'authentification suffit.

`PUBLIC_URL` figure aussi dans les origines CORS autorisées. C'est indispensable : derrière nginx, le backend reçoit la requête en `http` alors que le navigateur annonce `Origin: https://…`, et le filtre CORS de Quarkus la traiterait sinon comme une autre origine (login impossible). `PUBLIC_URL` doit donc être l'origine exacte (`https://hôte[:port]`, sans `/` final) : le démarrage échoue sinon, en indiquant la valeur à mettre.

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
- ⚠️ Limite connue : si Docker fait passer aussi le trafic du LAN par son *userland proxy* (configuration inhabituelle), un client du LAN arriverait par la passerelle, donc « de confiance ». À vérifier une fois sur le NAS : se connecter depuis un téléphone en 4G via le domaine et lire l'IP dans les logs du backend (`Connexion de '…' depuis <ip>`).
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
- Types MIME : `.mp4/.m4v` → `video/mp4`, `.mkv` → `video/x-matroska`, `.webm` → `video/webm`, `.avi` → `video/x-msvideo`, `.ts` → `video/mp2t`, `.ogm` → `video/ogg`.

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
  - traitement **par lots** de 500 vidéos (`library.batch-size`), une transaction par lot. Animés, saisons, épisodes et corrections sont chargés en mémoire une fois au début ; chaque lot écrit avec quelques requêtes **multi-lignes** en JDBC (`INSERT … ON CONFLICT … RETURNING`, `UPDATE … FROM (VALUES …)`). Hibernate n'est pas utilisé pour le scan : il ne sait pas regrouper des insertions sur des clés `IDENTITY` ;
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
- **Doublon d'épisode** (même animé, saison, épisode ; 12 cas `SxxExx`/`NxEE` relevés, dont 10 pour Devilman Crybaby ; d'autres apparaîtront avec les stratégies 3 et 5) : le fichier déjà lié est gardé (au premier scan : le premier par ordre de chemin), l'autre est signalé. Le choix de la meilleure version → FUTURE.
- **Double épisode** (`03-04`, `S01E03-E04`) et **numéro décimal** (`E05.5`, `0.89`, `Épisode 24.5`) : pas d'interprétation automatique, signalés.
- **Désaccord dossier / fichier** : importé selon le fichier, signalé.
- **Non résolu** : aucune stratégie ne répond et aucun marqueur d'extra.

### 7.6 Rapport de scan
- **Résumé par catégorie** : vidéos vues, épisodes reconnus, extras, non résolus, doublons, doubles épisodes / décimaux, désaccords dossier/fichier, fichiers disparus, autres fichiers (par type, dont sous-titres externes).
- **Liste filtrable** (par catégorie et par animé) des fichiers signalés, avec la raison et le chemin relatif. Réservé à l'admin.
- **Doublons et désaccords** : animé, saison, épisode, et **origine du numéro de saison** (`NAME_SXXEXX`, `NAME_NXEE`, `NAME_S` = « S1 » isolé dans le nom, `FOLDER` = dossier de saison, `SPECIAL_FOLDER` = dossier OAV/Bonus, `DEFAULT` = saison 1 faute d'indication, `OVERRIDE`). Pour un doublon : fichier **écarté** (`relativePath`), fichier **conservé** (`keptRelativePath`) et l'origine de la saison de chacun, de quoi repérer un sous-dossier non reconnu comme saison sans ouvrir les fichiers. `FullSampleScanTest` exporte les doublons du scan complet dans `backend/target/full-sample-duplicates.tsv`.

### 7.7 Correction manuelle
L'admin peut associer un fichier à un animé (par son **titre**, existant ou non), une saison et un numéro d'épisode, ou le marquer comme extra / ignoré. Il désigne le fichier par son **id** (`mediaFileId`, fourni par le rapport), jamais par un chemin. La correction est **stockée en base** (table `media_file_override`, clé : `relative_path`) et appliquée **à la place du parser** à chaque scan : un rescan ne l'écrase jamais. Elle prend effet **au scan suivant** (quelques secondes). Si le fichier disparaît, la correction est conservée (elle resservira s'il réapparaît au même chemin). `IGNORE` retire le fichier de la bibliothèque sans le toucher sur le disque.

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

**Résultats** sur l'échantillon complet : 99,86 % d'épisodes reconnus hors extras (27 643 / 27 682), 2,0 % d'extras. Scan complet des 32 940 chemins recréés en fichiers vides (`FullSampleScanTest`) : 27 591 épisodes, 572 extras, 52 doublons, 116 désaccords dossier/fichier, 26 non résolus, 8 doubles épisodes, 5 numéros décimaux, 1 316 animés visibles ; rescan identique, sans rien ajouter ni marquer disparu. (Avant la correction du « E » dans les empreintes CRC : 8 génériques pris pour des épisodes, 54 doublons.)

## 8. API (étape 1)

| Méthode | Route | Accès |
|---|---|---|
| GET | `/api/status` | public (`{"status":"UP"}`, vérifie la chaîne navigateur → nginx → backend) |
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
| GET | `/api/admin/library/scan-report` | ADMIN (résumé par catégorie du dernier scan) |
| GET | `/api/admin/library/issues?category=&anime=&scanId=&page=&size=` | ADMIN (liste filtrable et paginée du rapport, avec `mediaFileId` et chemin relatif) |
| PUT / DELETE | `/api/admin/library/files/{mediaFileId}/override` | ADMIN (correction manuelle d'un fichier, §7.7) |
| GET | `/api/admin/library/overrides` | ADMIN (liste des corrections) |
| GET / POST | `/api/admin/users` | ADMIN |
| PATCH | `/api/admin/users/{id}` | ADMIN (activer/désactiver, rôle, mot de passe) |

Lecture : seuls les épisodes dont le fichier est disponible sont visibles (sinon 404) ; `/api/anime?sort=title|recent` (recent = dernier fichier ajouté) ; saisons dans l'ordre 1, 2… puis « Spéciaux » (saison 0) ; aucun chemin de fichier dans les réponses. Pas de pagination (≈ 1 300 animés, liste légère) ; à ajouter si besoin. Un admin ne peut pas se désactiver ni retirer son propre rôle ADMIN (évite de se verrouiller dehors).
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
- Accessibilité / navigation clavier : `:focus-visible` très marqué, cibles ≥ 48 px, ordre de tabulation logique, liens et boutons natifs (pas de `div` cliquables).

## 10. Docker / déploiement

- `backend` : build multi-étapes (Maven + JDK 21 → JRE 21), user non-root, `JAVA_OPTS=-Xmx512m`.
- `web` : build multi-étapes (Node → `nginx:alpine`), config nginx : fichiers statiques + fallback SPA vers `index.html` + `proxy_pass /api` + `real_ip` (voir §5.4.1) + log sans query string sur `/api/stream/`.
- `postgres` : `postgres:16-alpine`, volume nommé, non publié, healthcheck ; le backend attend qu'il soit sain.
- Montage média : `${MEDIA_PATH}:/media:ro`.
- ⚠️ **Permissions Synology** : le processus du conteneur backend doit pouvoir **lire** le dossier du propriétaire du NAS (ACL DSM). On expose `PUID`/`PGID` pour faire tourner le backend avec un utilisateur qui a le droit de lecture ; à documenter dans le README.
- Images construites localement ou sur le NAS (`docker compose build`) ; pas de registre à cette étape.

### Variables d'environnement (`.env.example`)
`MEDIA_PATH`, `WEB_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`, `STREAM_SIGNING_SECRET`, `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD`, `PUBLIC_URL`, `COOKIE_SECURE`, `CORS_ORIGINS`, `SWAGGER_ENABLED`, `PUID`, `PGID`, `DOCKER_SUBNET`, `TRUSTED_PROXY_IPS`, `REFRESH_REUSE_GRACE_SECONDS`, `JAVA_OPTS`, `DEV_SPIKE_STREAM_ENABLED` et `DEV_MEDIA_PATH` (dev uniquement).

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
