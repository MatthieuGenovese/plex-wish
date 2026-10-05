# Roadmap — étape 1

Règles communes à chaque phase : projet compilable, tests verts, un commit, un court résumé, **puis attente de validation** avant la phase suivante.
Toute idée non essentielle va dans `docs/FUTURE.md`.

| # | Phase | Statut |
|---|---|---|
| — | Documents d'architecture | ✅ validés le 2026-09-29 |
| 0 | Spike vidéo | ✅ validé le 2026-09-29 sur fichiers synthétiques (Galaxy S24) — vrais fichiers, téléphone du propriétaire et sous-titres encore à tester (voir SPIKE.md §8) |
| 1 | Socle | ✅ validée le 2026-09-30 |
| 2 | Authentification | ✅ validée le 2026-09-30 |
| 3 | Bibliothèque | ✅ validée le 2026-10-02 |
| 4 | Interface web | ✅ validée le 2026-10-02 (correctifs : corrections en conflit, validés) |
| 5 | Streaming définitif et progression (backend) | ✅ validée le 2026-10-02 |
| 6 | Métadonnées (AniList) | ✅ validée le 2026-10-03 |
| 6.1 | Synopsis en français (TMDB) | ✅ validée le 2026-10-03 |
| 6.2 | Affiches stockées sur le NAS | ✅ validée le 2026-10-03 |
| 6.3 | Distribution et comédiens de doublage (AniList) | ✅ validée le 2026-10-03 |
| 7 | Application Android (téléphone) | en cours (connexion et bibliothèque livrées) |
| 8 | Traitement média (ffprobe, remux, sous-titres) | à faire, avant le lecteur web |
| 9 | Lecteur web | à faire |

---

## Documents ✅
- `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`.
- Validés, avec ajustements (voir §13 d'ARCHITECTURE.md).

## Phase 0 — Spike vidéo (jetable, ~½ journée)
But : vérifier que de vrais fichiers se lisent sur un téléphone Android **avant** d'investir dans le reste.
- `backend/` minimal (Quarkus) : `GET /api/dev/files` (liste numérotée) et `GET /api/dev/stream/{id}` qui sert un fichier de `dev-media` avec Range Requests (200/206/416, `Content-Range`, `Accept-Ranges`, MIME), streaming disque sans chargement en mémoire.
- Désactivé par défaut, activé par `DEV_SPIKE_STREAM_ENABLED=true`  ; le client ne donne qu'un numéro, jamais un chemin ; protection path traversal.
- Tests : plage de début, milieu, fin (`bytes=-500`), plage invalide (416), fichier absent, tentative de path traversal.
- `android/spike/` : Kotlin + Media3/ExoPlayer, un écran avec une URL éditable et un lecteur plein écran.
- `docs/SPIKE.md` : comment lancer, comment trouver l'IP du PC depuis le téléphone, comment connaître les codecs d'un fichier (`ffprobe` ou MediaInfo), et la grille à remplir (fichier, conteneur, codec vidéo, codec audio, image OK, son OK, seek OK, remarques) pour 5 à 10 vrais fichiers.
- **Sortie** : la grille remplie → décision ensemble : Direct Play suffisant / fallback ffmpeg à prévoir / remise en question.

## Phase 1 — Socle
- Structure du monorepo, `.gitignore`, `.gitattributes`, `dev-media/.gitkeep`.
- `backend/` : projet Quarkus propre (on reprend l'endpoint Range du spike), config par variables d'environnement, gestion d'erreurs JSON, OpenAPI/Swagger, health check, Flyway `V1__auth.sql` (app_user, refresh_token).
- `web/` : Angular standalone, routing avec pages vides (`/login`, `/`, `/anime`, `/anime/:id`, `/admin`), thème sombre de base, proxy de dev.
- `docker-compose.yml` (postgres, backend, web/nginx), Dockerfiles multi-étapes, `.env.example`.
- `README.md` : prérequis, lancement backend, Angular, Docker complet, dossier média, Swagger.
- Tests : démarrage de l'application + migrations Flyway OK, health check.
- **Sortie** : `docker compose up` démarre les trois services, la page Angular s'affiche et appelle `/api` via nginx.

## Phase 2 — Authentification
- Entité User, rôles ADMIN/USER, bcrypt, admin initial au premier démarrage.
- `/api/auth/login|refresh|logout`, `/api/me` ; access JWT 15 min + refresh token haché, rotation, cookie HttpOnly.
- Anti brute force (IP + username), prise en compte de `X-Forwarded-For`.
- `/api/admin/users` (liste, création, activation/désactivation, rôle, reset mot de passe).
- Tests : login OK/KO, refresh + rotation + réutilisation d'un token révoqué, logout, utilisateur désactivé, 401/403 par endpoint, blocage brute force, absence de mot de passe/token dans les logs.

## Phase 3 — Bibliothèque
Règles détaillées : ARCHITECTURE §7 (issues du relevé réel : 28 254 vidéos, 1 317 animés).
- Flyway `V3__library.sql` : anime, season, episode, media_file (avec `kind`), media_file_override, scan_run, scan_issue.
- `scripts/generate-fake-library.*` : bibliothèque factice (≈ 33 000 fichiers vides) dans un volume Docker, pour tester le scan complet.
- `FilenameParser` (chaînes uniquement) : `SxxExx` → `NxEE` → `E\d+` → numéro seul ; nom du fichier prioritaire, dossier de saison en secours et vérification ; saison 0 ; extras.
- Scan asynchrone par lots, un seul à la fois, sans ffprobe ; rapport par catégorie + liste filtrable ; corrections manuelles jamais écrasées.
- Endpoints de lecture `/api/anime…`, `/api/seasons…`, `/api/episodes…` ; endpoints admin de scan, de rapport et de correction.
- Tests : parser sur `backend/src/test/resources/library-sample.txt` (≥ 97 % d'épisodes reconnus + un test par piège), idempotence (rescan sans doublon, ajout, disparition, réapparition, correction conservée), garde-fous (/media vide, scan orphelin), path traversal, permissions.

## Phase 4 — Interface web
- Login, accueil (récemment ajoutés + bibliothèque), liste des animes, fiche anime (saisons/épisodes), admin (utilisateurs, lancement du scan, rapport).
- Token en mémoire, refresh au démarrage, interceptor, guards.
- Responsive, sombre, navigation clavier soignée. Pas de lecteur vidéo.
- Quelques tests : AuthService, interceptor, guards.
- Ajouts demandés : pagination + recherche sans accents côté API (`/api/anime`), historique des scans, `failureCode` (confirmation explicite avant `confirmMassRemoval`), délai affiché en cas de verrouillage, cookie `Secure` obligatoire hors dev, design tokens CSS.
- Vérifié dans le conteneur nginx (CSP réelle) avec la bibliothèque factice : parcours complet automatisé (Playwright/Chromium), aucune violation CSP.

## Fin de l'étape
Bilan ensemble, puis dans l'ordre prévu : streaming définitif (URL signées), lecteur web, progression, métadonnées, Android complet.
(Réalisé ensuite : streaming et progression en phase 5, métadonnées en 6, Android en 7 ; le lecteur web, oublié, devient la phase 9, après le traitement média en phase 8.)

## Phase 5 — Streaming définitif et progression (backend, testable avec curl)
- URL de lecture signée (HMAC, 6 h, liée au fichier et à l'utilisateur) et endpoint Range durci : ARCHITECTURE §6.
- Progression par utilisateur, « continuer à regarder », terminé au-delà de 90 % : ARCHITECTURE §6.3.
- Endpoint du spike `/api/dev/*` : **conservé derrière son drapeau** (`DEV_SPIKE_STREAM_ENABLED`, faux par défaut, absent de Docker Compose) tant que l'app Android du spike s'en sert. **À retirer** (code, tests, config, SPIKE.md §3) dès que l'app Android passe par `/api/episodes/{id}/stream-url` + `/api/stream/…`, au plus tard avec l'app Android complète. `ByteRange` et `VideoMediaTypes` restent (utilisés par le streaming définitif).
- Pas de lecteur web dans cette phase.

## Phase 6 — Métadonnées (AniList)
- Abstraction `MetadataProvider`, AniList comme premier fournisseur ; fournisseur et langue du synopsis enregistrés avec chaque fiche (ARCHITECTURE §15).
- Tâche de fond séparée du scan, idempotente, reprenable, respectueuse de la limite de débit ; la bibliothèque ne dépend jamais des métadonnées.
- Appariement par similarité de titre avec seuil de confiance ; non appariés et douteux corrigés par l'admin, corrections verrouillées avec confirmation.
- Affiches (URL), synopsis et année dans la grille, sur l'accueil et sur la fiche ; onglet admin « Métadonnées ».
- Mesuré sur la vraie liste (1 316 animés) contre AniList : voir le résumé de livraison.

## Phase 6.1 — Synopsis en français (TMDB) ✅
- Second fournisseur TMDB (clé facultative), appariement depuis les titres AniList, repli anglais ; conditions TMDB (6 mois, attribution, purge). ARCHITECTURE §16.

## Phase 6.2 — Affiches stockées sur le NAS ✅
- Téléchargement en tâche de fond (TMDB puis AniList), contrôles stricts, fichiers nommés par empreinte, service par identifiant aléatoire, repli distant. ARCHITECTURE §17.

## Phase 6.3 — Distribution et comédiens de doublage ✅
- AniList seule source, suites TV suivies jusqu'au nombre de saisons du dossier, 20 rôles au plus par animé, images sur le NAS. ARCHITECTURE §18–19.

## Phase 7 — Application Android (téléphone)
- Kotlin, Compose, Media3 dans `android/app`, structure prête pour la TV. ARCHITECTURE §20, `android/README.md`.
- Livré : connexion (refresh token natif chiffré par le Keystore), accueil, bibliothèque. Reste : fiche et distribution, page comédien, lecteur (URL signée), progression ; puis suppression de `android/spike` et de `/api/dev/stream`.

## Phase 8 — Traitement média (ffprobe, remux, sous-titres)
But : savoir ce que contient chaque fichier, et rendre lisibles sans ré-encodage ceux qui ne le sont que par leur conteneur. Prérequis du lecteur web (phase 9), utile aussi à l'app Android. **Le transcodage (ré-encodage vidéo ou audio) reste hors périmètre.**

Règles communes :
- `/media` reste en lecture seule ; rien n'y est jamais écrit. Tout résultat va dans un **dossier de cache à part** (volume dédié, ex. `${MEDIA_CACHE_PATH}:/cache`), nommé par identifiant en base, jamais d'après un nom venant du client.
- ffprobe et ffmpeg lancés par `ProcessBuilder` avec une **liste d'arguments fixe** (aucun shell, aucune chaîne construite par concaténation ; le seul argument variable est le chemin résolu depuis l'identifiant en base, précédé de `file:` pour qu'un nom de fichier ne soit jamais pris pour une option ou un protocole).
- Sur un NAS de 4 Go : un seul processus ffmpeg à la fois (ffprobe : 1 ou 2), priorité basse (`nice`/`ionice`), **délai maximal** par processus (puis arrêt forcé), **limite de mémoire** par processus (`prlimit --as`, ou un conteneur dédié avec `mem_limit` si c'est plus simple), sortie standard et erreur lues en continu et tronquées dans les logs.
- ffmpeg/ffprobe ajoutés à l'image du backend (environ +100 Mo) ; version affichée dans l'admin.

### 8.1 Analyse ffprobe du catalogue
- Tâche de fond séparée du scan (le scan reste sans ffprobe), reprenable, idempotente, relançable par l'admin ; incrémentale ensuite (fichiers nouveaux ou dont la taille ou la date de modification ont changé).
- `ffprobe -show_format -show_streams -show_chapters -of json`, en-têtes seulement (jamais `-count_frames` ni `-count_packets`, qui lisent tout le fichier).
- Stocké en base (nouvelle table liée à `media_file`) : durée ; conteneur réel ; vidéo (codec, profil, profondeur 8/10 bits, définition, images/s) ; pistes audio (codec, canaux, langue, titre, défaut) ; pistes de sous-titres (format texte ASS/SRT ou image PGS/VobSub, langue, titre, défaut, forcé) ; chapitres ; polices jointes (MKV) ; erreur de lecture le cas échéant. La durée renseigne `episode.duration`.
- **Rapport admin** par catégorie, avec liste filtrable comme le rapport de scan : *lisible sur Android* / *lisible dans un navigateur* / *remux nécessaire* (codecs acceptés, conteneur refusé : AVI, OGM, MKV pour le navigateur) / *transcodage nécessaire* (codec refusé : ex. audio AC3/DTS dans Chrome, HEVC selon le navigateur, sous-titres image dans un navigateur) / *illisible ou corrompu*. Règles de classement écrites au démarrage de la phase, prudentes (« navigateur » = Chrome et Firefox récents).

### 8.2 Remux sans ré-encodage, à la demande
- Copie des pistes (`-c copy`) dans un autre conteneur : AVI, OGM, MKV → MP4 (navigateur, quand les codecs le permettent) ou MKV (Android, pour AVI/OGM). Pas de ré-encodage : si un codec ne convient pas, le fichier reste dans « transcodage nécessaire ».
- Déclenché à la demande (lecture d'un fichier classé « remux nécessaire », ou action admin), file d'attente à un seul ffmpeg ; écriture dans un fichier temporaire du cache puis renommage (jamais de fichier à moitié écrit servi) ; servi par le même streaming signé (Range) que les originaux.
- Cache borné : **taille maximale** configurable, vérification de l'espace libre avant de commencer, **purge** des plus anciennement lus au-delà de la limite et action admin « vider le cache ». Le cache se régénère : rien à sauvegarder.

### 8.3 Extraction des sous-titres (pour le lecteur web)
- Sous-titres texte muxés (ASS/SSA/SRT) extraits dans le cache : WebVTT (lu nativement par le navigateur, sans les styles ASS) et ASS d'origine conservé pour un éventuel moteur de rendu ASS côté navigateur (décision en phase 9, avec les polices jointes).
- Sous-titres image (PGS, VobSub) : non extractibles en texte ; les afficher dans un navigateur demanderait de les incruster, donc du transcodage → hors périmètre, signalés dans le rapport.
- À la demande, mêmes limites (délai, mémoire, cache borné) : l'extraction lit tout le fichier, son coût disque est proche de celui d'un remux.

### Tests prévus
Analyse d'un JSON ffprobe enregistré (sans ffmpeg), classement par catégorie, arguments ffmpeg figés (aucune entrée client), délai dépassé → processus tué et fichier temporaire supprimé, purge du cache, aucune écriture hors du cache, permissions admin.

## Phase 9 — Lecteur web
- Lecteur `<video>` dans l'interface web sur l'URL signée : originaux lisibles par le navigateur, sinon fichier remuxé ; sous-titres WebVTT (ou rendu ASS) issus de la phase 8 ; reprise et progression comme sur Android ; message clair pour les fichiers classés « transcodage nécessaire ».
