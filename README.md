# Anime Server

Serveur de streaming d'animés auto-hébergé, pour un petit groupe privé, sur un NAS Synology (Docker).
Backend Quarkus + PostgreSQL, interface web Angular servie par nginx.

- Architecture et décisions : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Avancement : [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Application Android : [`android/README.md`](android/README.md)
- Spike vidéo (phase 0, compte rendu ; code retiré) : [`docs/SPIKE.md`](docs/SPIKE.md)

> État (2026-10-08) : serveur (bibliothèque, comptes, streaming par URL signée, progression, métadonnées AniList / TMDB, analyse ffprobe, remux à la demande des AVI et OGM), interface web refaite (Polish P2 : accueil, recherche et filtres, fiche, compte, thème clair) et **application Android** avec lecteur (`android/README.md`), validée sur un Galaxy S24. En cours : refonte de l'app Android (Polish P3). Pas encore de lecteur dans le navigateur (phase 10) : sur le web, « Voir l'épisode » ouvre la fiche, la lecture se fait dans l'app.

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
- Propriétés de configuration propres à l'application : toujours sous le préfixe **`anime.`** (`anime.auth.*`, `anime.library.*`, `anime.media.*`…). Quarkus refuse de démarrer si une propriété inconnue apparaît sous un préfixe mappé, et Maven ou la JVM définissent des propriétés système génériques (sous Windows, `mvnw quarkus:dev` définit `library.jansi.path`, qui bloquait le démarrage avec `SRCFG00050` quand le préfixe était `library`). Ne pas créer de préfixe générique (`library`, `auth`, `app`…). Les variables d'environnement (`MEDIA_ROOT`, `JWT_SECRET`…) ne changent pas. Test : `MavenSystemPropertiesTest`.

## Lancer le front (dev)

```powershell
cd web
npm ci
npm start                        # http://localhost:4200
```

`/api` est redirigé vers `localhost:8080` (`proxy.conf.json`) : lancer le backend avant. Tests : `npm test`.

## Lancer le tout avec Docker (PC : développement et essais)

> **Sur le NAS, ne pas utiliser cette méthode** : installation en une commande avec des images déjà compilées, secrets créés tout seuls, assistant dans le navigateur, sauvegardes et mises à jour automatisées. Tout est dans [`docs/DEPLOIEMENT.md`](docs/DEPLOIEMENT.md).

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

### Sur le NAS

Voir [`docs/DEPLOIEMENT.md`](docs/DEPLOIEMENT.md) (installation, accès Internet, sauvegardes, mises à jour, dépannage) et, pour guider la personne qui héberge le NAS, [`docs/AIDE-DEPLOIEMENT-AMI.md`](docs/AIDE-DEPLOIEMENT-AMI.md). Le NAS ne compile rien : les images sont construites sur le PC et publiées (`scripts/publish-images.ps1`, refus si un secret est trouvé dans une image).

Dans cette pile de développement, la porte web sert tout le site (`LAN_ENTRY=local`) et l'admin initial vient de `.env` ; sur le NAS, l'admin est créé par l'assistant de premier lancement et le site public passe par Caddy (HTTPS).

## Dossier média (pile de développement)

Sur le NAS, l'utilisateur qui lit les vidéos est trouvé tout seul au démarrage (conteneur `init`, [`docs/DEPLOIEMENT.md`](docs/DEPLOIEMENT.md)).

- `MEDIA_PATH` (dans `.env`) = dossier des vidéos **sur l'hôte**, par ex. `/volume1/animes` sur le NAS, `./dev-media` en local.
- Il est monté **en lecture seule** dans le backend (`/media`). L'application ne modifie, ne déplace et ne supprime jamais un fichier.
- Droits : le backend tourne avec l'utilisateur `PUID:PGID` (`.env`). Il doit avoir le droit de **lire** le dossier. Sur Synology, trouver ces numéros en SSH avec `id <utilisateur>` (un utilisateur DSM qui a accès en lecture au dossier partagé).
- `dev-media/` sert aux tests locaux : son contenu n'est jamais versionné.

## Administrateur initial (pile de développement)

Sur le NAS, pas de mot de passe par défaut : l'administrateur est créé par l'assistant de premier lancement, et les autres comptes par des liens d'invitation à usage unique.

Renseigner `INITIAL_ADMIN_USERNAME` et `INITIAL_ADMIN_PASSWORD` (10 caractères minimum) dans `.env` avant le premier lancement : le compte est créé au démarrage **s'il n'existe aucun admin**. Ensuite les deux variables sont ignorées et peuvent être retirées. L'admin crée les autres comptes (`POST /api/admin/users`) ; il n'y a pas d'inscription publique.

## Sécurité : ce qu'il faut régler (pile de développement)

Sur le NAS, les secrets sont créés au premier démarrage et `PUBLIC_URL` vient du nom de domaine : rien à régler à la main ([`docs/DEPLOIEMENT.md`](docs/DEPLOIEMENT.md)). Le reste de cette section concerne `docker-compose.yml`.

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

La lecture se fait dans l'application Android (`android/README.md`) ; pas encore de lecteur dans le navigateur (phase 10). Pour tester l'API à la main : `curl` (ou VLC, qui accepte une URL).

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

Sur la fiche d'un animé, la **distribution** : les **doubleurs japonais** (photo et nom), chacun avec le nom du personnage qu'il joue dans cet animé et son rôle (principal ou secondaire), sans image de personnage ; un clic sur un comédien ouvre sa page avec les animés **de la bibliothèque** où il joue. Source unique : AniList (ARCHITECTURE §18-19).

- Tâche de fond, **après** les métadonnées (elle attend que celles-ci n'aient plus rien à faire) : ~1 h 20 de requêtes AniList pour ~1 300 animés au premier passage, puis ~35 min à 1 h de photos de comédiens. Les suites (saisons 2, 3…) sont suivies jusqu'au nombre de saisons du dossier.
- `CAST_MAX_ROLES` (20 par défaut) : rôles gardés par animé. Photos des comédiens dans `POSTERS_HOST_PATH/cast`, **≈ 0,35 à 0,6 Go** au total (les anciennes images de personnages sont supprimées au démarrage de cette version).
- *Administration → Distribution* : avancement, place, animés sans distribution, « Relancer », « Effacer toute la distribution ». `CAST_ENABLED=false` arrête la récupération.

## Analyse des fichiers (ffprobe) et onglet « Médias »

Après chaque scan, une tâche de fond analyse les épisodes avec ffprobe (en-têtes seulement), un fichier à la fois, en priorité basse, en pause pendant un scan ; ensuite, seulement les fichiers nouveaux ou modifiés. Elle renseigne la **durée des épisodes** (web et app) et classe chaque fichier : *lisible sur Android*, *remux nécessaire* (AVI, OGM), *transcodage nécessaire*, et *lisible ou non dans un navigateur* avec les raisons (ARCHITECTURE §22). `MEDIA_PROBE_ENABLED=false` l'arrête.

- **Premier passage** (~28 000 fichiers) : estimé à **45 min à 2 h 30** sur le DS923+ (0,1 à 0,3 s par fichier : démarrage de ffprobe et quelques lectures dispersées sur les disques ; mesuré : 0,35 à 1,8 Mo lus par fichier, soit **15 à 30 Go au total, en petits morceaux**). En priorité « idle », elle cède le disque à la lecture des vidéos : plus longue si quelqu'un regarde en même temps. Rien à planifier.
- *Administration → Médias* : avancement, répartition, liste filtrable (pistes, raisons), « Réanalyser », espace à prévoir pour remuxer tous les AVI/OGM.

### Test à blanc du remux

Avant d'activer le remux (phase 9.2), l'onglet « Médias » propose un **test à blanc** : chaque AVI/OGM passe dans ffmpeg avec les deux commandes candidates (`-fflags +genpts`, et `+ -bsf:v mpeg4_unpack_bframes` pour le Xvid/DivX), vers une sortie nulle : **rien n'est écrit**, mais tout le fichier est lu. Résultat par commande et raison des échecs.

- **Durée** (~920 fichiers, ~150 Go) : chaque fichier est lu une fois sur le disque (la seconde commande le relit depuis la mémoire, ~200 Mo par fichier). À 100-150 Mo/s, compter **30 min à 1 h 30**, davantage si la lecture est ralentie (priorité « idle »). Charge : lecture séquentielle soutenue des disques, processeur peu sollicité (copie sans ré-encodage).
- **Hors des heures d'usage** : *Médias → Test à blanc → « Ou à » 02:00 → Programmer* (dans les 24 h ; perdu si le conteneur redémarre avant). « Arrêter » à tout moment ; relancé, il reprend où il s'était arrêté. Pendant le test, l'analyse ffprobe est en pause.

### Remux à la demande (AVI, OGM)

Les AVI et OGM ne se lisent pas tels quels sur Android. À la première lecture d'un tel épisode, le serveur en fabrique une copie MKV **sans ré-encodage** (quelques secondes, l'app affiche « Préparation de l'épisode… »), gardée dans un cache et resservie ensuite immédiatement (ARCHITECTURE §23). Le navigateur ne les lit pas, même convertis : la fiche web l'indique.

1. **Créer le dossier** sur le NAS, hors du dossier média : `mkdir -p /volume1/docker/anime-server/remux-cache`.
2. **Droits** : `sudo chown -R <PUID>:<PGID> /volume1/docker/anime-server/remux-cache` (comme pour les affiches).
3. Dans `.env` : `REMUX_CACHE_PATH=/volume1/docker/anime-server/remux-cache` et `REMUX_CACHE_MAX_GB=50`, puis `docker compose up -d`.
4. **Taille** : ~200 Mo par épisode ; 50 Go ≈ 250 épisodes (tout le catalogue AVI/OGM dépasse 150 Go : inutile de tout garder). Plein, le cache efface les copies les moins récemment regardées (jamais une copie regardée dans les 3 dernières heures). Le vider est sans risque : tout se régénère.
5. *Administration → Médias → Remux à la demande* : occupation, file d'attente, échecs (« Relancer »), « Préparer l'animé » (avant une soirée, par exemple), « Vider le cache ». Si le dossier n'est pas inscriptible, un avertissement l'indique et les AVI/OGM répondent « indisponible » ; le reste fonctionne.

Une demande de lecture passe avant les tâches de fond (analyse, test à blanc), qui se mettent en pause le temps du remux.

## Accès depuis Internet, sauvegardes, mises à jour

Sur le NAS : [`docs/DEPLOIEMENT.md`](docs/DEPLOIEMENT.md). En résumé : HTTPS par Caddy (Let's Encrypt) derrière une redirection du port 443 de la box vers le port 8443 du NAS, nom DuckDNS mis à jour par le serveur ; plan B Tailscale Funnel si la box est derrière un CGNAT ; Cloudflare Tunnel exclu (ses conditions interdisent la vidéo sur les offres gratuites). Sauvegarde vérifiée chaque nuit par le conteneur `backup`, mise à jour par `update.sh` avec retour arrière automatique.

Pile de développement : `scripts/backup-db.sh` et `scripts/restore-db.sh` sauvegardent et restaurent la base de `docker-compose.yml` (à la main).

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

## Catalogue de démonstration (interface, développement seulement)

Pour juger l'interface avec un vrai volume sans toucher à la bibliothèque : 1 300 animés inventés, ~33 000 épisodes, affiches générées, titres longs, synopsis absents ou très longs, progression (docs/DESIGN.md §6).

```sh
scripts/demo-catalog.sh        # ou scripts\demo-catalog.ps1 sous Windows
# → http://localhost:8090 (compte admin du .env)
scripts/demo-catalog.sh down   # arrête et efface la stack de démonstration
```

Pour la reconstruire avec le web à jour (après un `git pull`) : `scripts\demo-catalog.ps1 down` puis `scripts\demo-catalog.ps1` (ou `.sh`) ; le second lance `docker compose up --build`, donc les images backend et web sont reconstruites depuis le dépôt, et la progression de démonstration (épisodes en cours, « À suivre », un animé ajouté il y a deux heures) est créée pour le compte admin. Un compte créé ensuite n'a aucun historique (utile pour voir les états vides).

Stack Docker séparée (`plexwish-demo` : sa base, son réseau, port `DEMO_WEB_PORT`, 8090 par défaut), sans aucune tâche qui irait sur Internet. Jamais sur le NAS ; ne pas y lancer de scan ; les épisodes n'ont pas de fichier (lecture impossible).

## Swagger / OpenAPI

- En dev : <http://localhost:8080/q/swagger-ui>, toujours actif.
- Avec Docker : mettre `SWAGGER_ENABLED=true` dans `.env`, relancer, puis `http://<hôte>:<WEB_PORT>/q/swagger-ui`. À remettre à `false` ensuite.
