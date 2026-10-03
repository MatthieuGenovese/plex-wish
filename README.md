# Anime Server

Serveur de streaming d'animés auto-hébergé, pour un petit groupe privé, sur un NAS Synology (Docker).
Backend Quarkus + PostgreSQL, interface web Angular servie par nginx.

- Architecture et décisions : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Avancement : [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Spike vidéo (phase 0) : [`docs/SPIKE.md`](docs/SPIKE.md)

> État (2026-10-03) : **phases 0 à 6.2 terminées**. Connexion, bibliothèque, fiche anime et administration (phases 1 à 4) ; streaming par URL signée avec Range et progression par utilisateur, testables avec `curl` (phase 5) ; métadonnées AniList : affiches, synopsis anglais, année (phase 6) ; synopsis en français via TMDB (6.1) ; affiches stockées sur le NAS (6.2). Pas encore de lecteur vidéo web ni d'application Android complète.

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

- Trois conteneurs : `postgres` (non exposé), `backend` (non exposé), `web` (nginx, seul port publié, sur `127.0.0.1` par défaut : voir `WEB_BIND`).
- Arrêt : `docker compose down`. Les données restent dans le volume `pgdata` (`down -v` les efface).
- Mise à jour du code : `docker compose up -d --build`.

### Générer les secrets

`JWT_SECRET` et `STREAM_SIGNING_SECRET` : 32 caractères au moins, **différents** l'un de l'autre. Lancer la commande deux fois, une valeur pour chacun (64 caractères hexadécimaux) :

```powershell
# Windows PowerShell (5.1 ou 7)
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') })
```

```sh
# Linux, macOS, NAS en SSH
openssl rand -hex 32
```

Les coller dans `.env` (jamais dans un fichier versionné). Changer `JWT_SECRET` invalide les jetons d'accès en cours (renouvelés automatiquement, sans reconnexion) ; changer `STREAM_SIGNING_SECRET` invalide les liens de lecture en cours. Même méthode pour `POSTGRES_PASSWORD` (avant le premier lancement : il est fixé à la création de la base).

### Sur le Synology (Container Manager)

1. Copier le dépôt sur le NAS (ex. `/volume1/docker/anime-server`) et créer `.env` à partir de `.env.example`.
2. Container Manager → **Projet** → **Créer** → chemin du dossier → il détecte `docker-compose.yml`.
3. Reverse proxy DSM (*Panneau de configuration → Portail de connexion → Avancé → Proxy inversé*) : `https://<nom public>` → `http://127.0.0.1:<WEB_PORT>`. Le HTTPS se termine au DSM. Voir « Accès depuis Internet » et la « Checklist de déploiement sur le DSM ».

### Construire les images sur un NAS à 4 Go de RAM ?

Mesuré le 2026-10-03 (machine à 2 cœurs, 8 Go) : compilation du backend (Maven) **26 s, pic 660 Mo** ; interface web (Angular) **19 s, pic 940 Mo**. Premier build : environ **1,3 Go à télécharger** (images Maven 810 Mo et Node 240 Mo, dépendances ~220 Mo et ~300 Mo) et ~2,5 Go de disque pour les images de build et leur cache.

Sur un DS923+ (Ryzen R1600, 2 cœurs, 4 Go) : faisable, mais `docker compose up -d --build` construit les deux images **en parallèle** (~1,6 Go) pendant que la stack tourne (backend ~0,7 Go, PostgreSQL ~0,2 Go) et que le DSM occupe 1 à 1,5 Go : le NAS risque de passer en swap, lent mais sans casse. Compter 10 à 20 min la première fois (téléchargements, disques durs), quelques minutes ensuite. Pour limiter la mémoire, construire l'une après l'autre :

```sh
docker compose build backend && docker compose build web && docker compose up -d
docker image prune -f        # enlève les anciennes images remplacées
```

**Repli si le NAS peine** (non outillé, à faire à la main) : construire sur le PC, exporter, importer sur le NAS.

```powershell
docker compose build                                            # sur le PC
docker save anime-server-backend anime-server-web -o anime-images.tar   # ~180 Mo (mesuré)
```
```sh
docker load -i anime-images.tar          # sur le NAS, après copie du fichier (accepte aussi un .tar.gz)
docker compose up -d --no-build
```

Le DS923+ et un PC Windows classique sont tous deux en `amd64` : les images sont compatibles. Depuis un Mac à puce Apple, ajouter `--platform linux/amd64` au build. Avec ce repli, Maven et Node ne sont jamais téléchargés sur le NAS.

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
- adresse en HTTP depuis une autre machine (`http://192.168.1.20:8080`, seulement avec `WEB_BIND=0.0.0.0`) : la connexion semble réussir mais la session est perdue au rechargement (F5). Passer par le reverse proxy HTTPS du DSM.

En développement (`mvnw quarkus:dev`), `Secure` est désactivé par défaut (`COOKIE_SECURE=false`).

### Port publié : `WEB_BIND`

Le port `WEB_PORT` est publié **sur `127.0.0.1` seulement** par défaut (`WEB_BIND=127.0.0.1`) : seul le NAS lui-même y accède, donc le reverse proxy du DSM, et personne du réseau local ne peut contourner le HTTPS en appelant `http://<ip-du-nas>:8080`. La détection de la vraie IP n'est pas affectée (vérifié avec `scripts/check-client-ip.sh`). Dans la règle du reverse proxy, mettre `127.0.0.1` comme destination, pas `localhost`. Pour un essai sans reverse proxy depuis une autre machine : `WEB_BIND=0.0.0.0`. Sur un PC, `http://localhost:8080` fonctionne dans les deux cas.

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
- **Affiches** : téléchargées sur le NAS (voir ci-dessous) ; en attendant, ou si le dossier n'est pas utilisable, le navigateur les charge depuis leur source.
- **Administration → Métadonnées** : avancement, non appariés et douteux, correction par candidat ou par identifiant AniList (le nombre dans `anilist.co/anime/<id>`), « aucune fiche ». Une correction est verrouillée : jamais écrasée. Remplacer une fiche existante demande une confirmation.

### Synopsis en français (TMDB, facultatif)

Sans configuration, les synopsis restent en anglais. Pour les avoir en français quand ils existent :

1. Créer un compte sur [themoviedb.org](https://www.themoviedb.org), puis *Paramètres → API* : demander une clé (usage personnel, non commercial).
2. Copier le **jeton d'accès en lecture** (« API Read Access Token », le long) dans `.env` : `TMDB_READ_TOKEN=…`, puis `docker compose up -d`.
3. *Administration → Synopsis français* : avancement (après AniList, quelques minutes), et animés restés sans synopsis français, à corriger à la main au besoin (adresse de la fiche TMDB).

Le jeton reste côté serveur (jamais envoyé au navigateur ni écrit dans les logs). **Conditions TMDB** : usage non commercial ; rien n'est conservé plus de 6 mois (fiches redemandées à 5 mois, effacées à 6) ; logo et mention dans *À propos* (déposer le logo officiel dans `web/public/attribution/tmdb-logo.svg`, voir le README de ce dossier) ; pour arrêter, bouton « Effacer toutes les données TMDB » puis retirer le jeton.

### Affiches sur le NAS

Une tâche de fond (séparée du scan) télécharge l'affiche de chaque animé, **TMDB d'abord, AniList sinon**, dans un dossier à part : les navigateurs ne contactent plus TMDB ni AniList (adresse IP des spectateurs, disponibilité). Environ 100 Ko par animé, soit ~150 Mo pour 1 300 animés ; premier passage en une quinzaine de minutes (une image toutes les 0,5 s).

1. **Créer le dossier** sur le NAS, **hors du dossier média** (qui reste en lecture seule et n'est jamais modifié), par exemple `/volume1/docker/anime-server/posters` (File Station ou SSH : `mkdir -p /volume1/docker/anime-server/posters`).
2. **Droits** : le conteneur backend tourne avec `PUID:PGID` (voir `.env`). Le dossier doit lui appartenir : en SSH, `sudo chown -R <PUID>:<PGID> /volume1/docker/anime-server/posters` (ou, dans File Station, donner Lecture/Écriture à cet utilisateur).
3. Dans `.env` : `POSTERS_HOST_PATH=/volume1/docker/anime-server/posters`, puis `docker compose up -d`.
4. *Administration → Affiches* : nombre d'affiches sur le NAS, distantes, absentes ou en échec, place utilisée et estimée, bouton « Retélécharger ». Si le dossier n'est pas accessible en écriture, un avertissement l'indique (et le journal du backend aussi) : rien ne casse, les affiches restent distantes.

Si un fichier disparaît (dossier vidé, disque changé), l'affiche distante est affichée et le fichier retéléchargé automatiquement. Affiches TMDB : retéléchargées à 5 mois, effacées à 6 (conditions TMDB), et effacées par « Effacer toutes les données TMDB ». `POSTERS_ENABLED=false` : pas de téléchargement.

### Distribution (personnages et comédiens)

Sur la fiche d'un animé, la **distribution** : personnages (image, rôle principal ou secondaire) et leurs **doubleurs japonais** ; un clic sur un comédien ouvre sa page avec les animés **de la bibliothèque** où il joue. Source unique : AniList (ARCHITECTURE §18-19).

- Tâche de fond, **après** les métadonnées (elle attend que celles-ci n'aient plus rien à faire) : ~1 h 20 de requêtes AniList pour ~1 300 animés au premier passage, puis ~4 h d'images. Les suites (saisons 2, 3…) sont suivies jusqu'au nombre de saisons du dossier.
- `CAST_MAX_ROLES` (20 par défaut) : rôles gardés par animé. Images dans `POSTERS_HOST_PATH/cast`, **≈ 1 Go** au total.
- *Administration → Distribution* : avancement, place, animés sans distribution, « Relancer », « Effacer toute la distribution ». `CAST_ENABLED=false` arrête la récupération.

## Accès depuis Internet

L'application doit être servie en **HTTPS** (cookie de session `Secure`) sous **une seule adresse**, celle de `PUBLIC_URL`. Trois façons de faire, vérifiées le 2026-10-03 :

### Option A : DDNS Synology + certificat Let's Encrypt + reverse proxy du DSM (recommandée)

1. *Panneau de configuration → Accès externe → DDNS → Ajouter* : fournisseur **Synology**, nom `monnas.synology.me` (gratuit, compte Synology), cocher l'obtention du certificat **Let's Encrypt**. Le DSM le renouvelle seul (validité 90 jours).
2. Reverse proxy (*Portail de connexion → Avancé → Proxy inversé*) : source `HTTPS`, nom d'hôte `monnas.synology.me`, port `443` → destination `HTTP`, `127.0.0.1`, port `WEB_PORT` (voir « Port publié : `WEB_BIND` »). Puis `PUBLIC_URL=https://monnas.synology.me`. L'application est à la racine de ce nom ; le DSM lui-même reste sur ses ports 5000/5001.
3. Box : rediriger le port **443** (TCP) vers le NAS. Ne **pas** rediriger 5000/5001 (interface du DSM).

Limites :
- **Il faut une IPv4 publique joignable.** Certains fournisseurs partagent une IPv4 entre plusieurs clients (CGNAT) : aucune connexion entrante possible. Vérifier : si l'adresse WAN affichée par la box (10.x, 100.64–127.x…) diffère de celle d'un site comme « quel est mon IP », la ligne est en CGNAT. Free : demander une « adresse IPv4 fixe full-stack » dans l'espace abonné (irréversible, à redemander après un déménagement). SFR : IPv4 partagée sur certaines lignes, une « IPv4 full stack » s'obtient en appelant l'assistance. Orange fibre : pas de partage d'IPv4 à ce jour d'après Orange (été 2025), prévu plus tard avec une option pour s'en sortir. Autres : à vérifier.
- **Port 80** : pas nécessaire pour le certificat d'un nom `synology.me` (le DSM le valide par le DNS de Synology). Port 443 : nécessaire (sinon il faut un port non standard dans `PUBLIC_URL`, que certains réseaux d'entreprise ou de wifi publics bloquent).
- Le NAS est exposé sur Internet (port 443) : DSM à jour, blocage automatique des IP activé, 2FA sur les comptes DSM.
- Depuis le réseau local, `monnas.synology.me` doit aussi répondre : la plupart des box gèrent ce « retour » (NAT loopback) ; sinon, ajouter le nom dans le DNS local.

### Option B : nom de domaine personnel

Même chose avec `anime.mondomaine.fr` (environ 10 € par an) : un enregistrement DNS `CNAME` vers `monnas.synology.me` suit l'IP dynamique, puis certificat Let's Encrypt pour ce nom dans *Sécurité → Certificat*. Limites : les mêmes que A, plus le **port 80** à rediriger vers le NAS pour la validation Let's Encrypt (défi HTTP-01, uniquement sur le port 80) à chaque renouvellement. Un certificat générique (`*.mondomaine.fr`) demande la validation par DNS (API du registraire), pas gérée simplement par le DSM.

### Option C : Tailscale (réseau privé, sans ouvrir de port)

Paquet Tailscale pour le DSM ; chaque spectateur installe Tailscale et rejoint le réseau. Aucun port ouvert, **fonctionne derrière un CGNAT** (connexions sortantes ; si la connexion directe est impossible, le trafic passe par les relais Tailscale, plus lents). HTTPS : certificat fourni par Tailscale pour le nom en `.ts.net` (fonction HTTPS à activer).

Limites :
- Formule gratuite « Personal » : **6 utilisateurs** au plus (3 avant avril 2026), appareils illimités. Pour ~10 amis, il faut la formule payante ou les faire passer par le partage d'appareil (à vérifier avant).
- Clients officiels : Windows, macOS, Linux, iOS, Android, Apple TV, Amazon Fire. **Pas de Samsung (Tizen) ni de LG (webOS)** : ces TV n'y ont pas accès (sauf un routeur de sous-réseau Tailscale chez le spectateur, peu réaliste).
- Chaque ami doit installer et laisser actif un client VPN : bonne option pour l'admin ou un dépannage, contraignante pour tout le groupe.

### En résumé

| | A : DDNS Synology | B : domaine perso | C : Tailscale |
|---|---|---|---|
| IPv4 publique (pas de CGNAT) | requise | requise | non |
| Ports à rediriger | 443 | 443 et 80 | aucun |
| Coût | gratuit | ~10 €/an | gratuit jusqu'à 6 utilisateurs |
| Installation chez les amis | rien (navigateur, app) | rien | client Tailscale |
| TV Samsung / LG (plus tard) | oui | oui | non |

## Checklist de déploiement sur le DSM

À dérouler au premier déploiement, puis après tout changement réseau (box, reverse proxy, `DOCKER_SUBNET`). Commandes en SSH sur le NAS, depuis le dossier du dépôt, avec `sudo` si nécessaire.

1. **Droits PUID/PGID.** `id <utilisateur>` donne les numéros à mettre dans `.env`. Vérifier depuis le conteneur :
   ```sh
   docker compose exec backend sh -c 'id; ls /media | head -3'                       # lecture du dossier média
   docker compose exec backend sh -c 'touch /data/posters/.t && rm /data/posters/.t && echo écriture OK'
   ```
   Le dossier média doit être **lisible** (jamais besoin d'écriture : il est monté en lecture seule) ; le dossier des affiches doit être **inscriptible** (*Administration → Affiches* ne doit pas afficher d'avertissement).
2. **Démarrage.** `docker compose ps` : trois conteneurs `healthy`. Sinon `docker compose logs backend` dit quoi corriger (secrets, `PUBLIC_URL`).
3. **Reverse proxy.** Destination `http://127.0.0.1:<WEB_PORT>` : écrire `127.0.0.1`, **pas** `localhost` (qui peut désigner l'adresse IPv6 `::1`, sur laquelle le port n'écoute pas).
4. **IP des clients.** `ADMIN_USER=admin ADMIN_PASSWORD='…' scripts/check-client-ip.sh` → trois lignes `OK`.
5. **Test depuis la 4G.** Wifi coupé sur un téléphone : ouvrir `https://<nom public>`, se connecter, recharger la page (F5 : la session doit tenir). Puis `docker compose logs backend | grep Connexion` : l'IP affichée est celle de l'opérateur mobile, pas `172.30.64.1`.
6. **Lecture et seek d'un gros fichier à travers le reverse proxy.** Prendre un des plus gros fichiers (`find /volume1/animes -size +2G | head`), le lire sur le téléphone en 4G :
   - aller à 80 % puis revenir au début : l'image doit repartir en quelques secondes ;
   - mettre en pause **plus d'une minute**, puis reprendre : la connexion est fermée au bout d'environ 60 s de pause (délai par défaut de nginx, côté DSM comme côté application, mesuré) et le lecteur doit la rouvrir seul à la bonne position. S'il reste bloqué, le noter (à traiter avec le lecteur Android) ;
   - **mise en tampon côté DSM** : un nginx réglé par défaut, comme le reverse proxy du DSM, recopie la vidéo dans un fichier temporaire sur le disque système quand le spectateur lit moins vite que le NAS n'envoie (mesuré : **1 Go écrit en 5 secondes pour un seul spectateur** lent). Corrigé : le nginx de l'application envoie `X-Accel-Buffering: no` sur `/api/stream/`, que le nginx du DSM respecte (vérifié avec un nginx réglé comme le DSM : plus de fichier temporaire, seek inchangé). Pendant la lecture, `df -h /` sur le NAS ne doit pas bouger.
   - les délais d'attente du reverse proxy (*Paramètres avancés* de la règle, 60 s par défaut) n'ont pas besoin d'être changés pour la lecture : la vidéo arrive en continu.
7. **Sauvegarde.** Tâche planifiée créée et exécutée une fois (voir « Sauvegarde de la base ») ; le fichier `.dump` existe, sur un autre disque ou copié ailleurs.
8. **Métadonnées et affiches.** *Administration → Métadonnées*, *Synopsis français*, *Affiches* : la récupération avance (premier passage ~1 h pour AniList, puis TMDB et affiches).

## Sauvegarde de la base

Toutes les données de l'application sont dans PostgreSQL (volume Docker `pgdata`) ; les vidéos ne sont jamais modifiées et ne font pas partie de la sauvegarde.

| Perdu sans sauvegarde (à sauvegarder) | Régénérable (pas besoin de sauvegarde) |
|---|---|
| Comptes, rôles, mots de passe (empreintes) | Bibliothèque (animés, saisons, épisodes) : nouveau scan de `/media` |
| Progressions de lecture (« Continuer à regarder ») | Métadonnées AniList et TMDB : retéléchargées par les tâches de fond (~1 h pour AniList) |
| **Corrections manuelles** : fichiers rattachés à la main, appariements AniList / TMDB verrouillés | Affiches du dossier `POSTERS_HOST_PATH` : retéléchargées |
| Historique des scans (rapports) | Sessions : il suffit de se reconnecter |

Attention : les progressions et les corrections pointent vers les fichiers de la base. Après une perte de la base, un nouveau scan recrée la bibliothèque, mais **pas** les progressions ni les corrections. D'où la sauvegarde.

### Sauvegarder : `scripts/backup-db.sh`

```sh
scripts/backup-db.sh                                     # depuis la racine du dépôt, stack démarrée
BACKUP_DIR=/volume1/backups/anime-server BACKUP_KEEP=30 scripts/backup-db.sh
```

- Un fichier par exécution, `anime-db-AAAAMMJJ-HHMMSS.dump` (format compressé de `pg_dump`, quelques dizaines de Ko à quelques Mo), vérifié avec `pg_restore --list` avant d'être gardé.
- Rotation : seules les `BACKUP_KEEP` dernières (14 par défaut) sont conservées. Dossier : `BACKUP_DIR` (défaut `<dépôt>/backups`, ignoré par git).
- Aucun mot de passe : le dump est fait dans le conteneur `postgres` (connexion locale). Le fichier est lisible par son seul propriétaire (il contient les empreintes des mots de passe).
- Mettre le dossier de sauvegarde **sur un autre volume ou un autre appareil** (Hyper Backup, Synology Drive, disque USB) : une sauvegarde sur le même disque ne protège pas d'une panne de ce disque.

### Planifier avec le Planificateur de tâches du DSM

*Panneau de configuration → Planificateur de tâches → Créer → Tâche planifiée → Script défini par l'utilisateur* :

1. **Général** : nom « Sauvegarde anime-server », utilisateur **root** (nécessaire pour Docker).
2. **Programmer** : tous les jours, par exemple à 4 h.
3. **Paramètres de tâche** : cocher « Envoyer les détails de l'exécution par e-mail » et « uniquement si le script se termine de manière anormale » (le script sort en erreur si la sauvegarde échoue). Script :

   ```sh
   BACKUP_DIR=/volume1/backups/anime-server BACKUP_KEEP=30 /volume1/docker/anime-server/scripts/backup-db.sh
   ```
4. Clic droit sur la tâche → **Exécuter**, puis vérifier qu'un fichier `.dump` est apparu dans le dossier.

### Restaurer : `scripts/restore-db.sh`

```sh
scripts/restore-db.sh /volume1/backups/anime-server/anime-db-20261003-040000.dump
```

Le script vérifie le fichier, demande de taper `RESTAURER`, fait une **sauvegarde de sécurité** de la base actuelle (`avant-restauration-….dump`, 5 gardées), arrête le backend et le web, recrée la base vide, restaure en une seule transaction et redémarre. Une sauvegarde d'une version plus ancienne de l'application est mise à niveau au démarrage (migrations Flyway). En cas d'échec, il affiche la commande pour revenir à l'état d'avant. `RESTORE_YES=1` évite la question (script).

Testé sur la stack Docker : base abîmée (utilisateur, bibliothèque et progressions supprimés), restauration, puis connexion avec l'utilisateur supprimé ; fichier tronqué refusé sans rien toucher ; rotation à 2 fichiers.

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
