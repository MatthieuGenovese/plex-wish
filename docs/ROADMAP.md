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
| 7 | Application Android (téléphone) | lecteur validé le 2026-10-06 sur MKV et MP4 (son, sous-titres) ; AVI et OGM → remux (phase 9) ; spike et `/api/dev/*` supprimés le 2026-10-08 |
| 9 | Traitement média (ffprobe, remux) — **avant la phase 8** | 9.1 analyse du catalogue : livrée ; 9.2 remux à la demande : livrée le 2026-10-06, en attente de validation (S24, NAS) |
| P | **Polish** (interface web et Android) — **avant le déploiement sur le NAS et avant la phase 8** | P1, P2 (serveur et web), 9.2 et P3.0 (chaîne Android) validées ; P3.1–P3.7 (app Android) et suppression du spike livrées le 2026-10-08, en attente des essais sur le S24 (DESIGN §13) |
| D1 | **Déploiement sur le NAS** (préparation, testée sur une pile Docker propre) | D1a (D1.1 à D1.7) livrée le 2026-10-09 : images, secrets, assistant, Caddy et DuckDNS, invitations, sauvegardes, mises à jour, installateur, docs ; puis installation d'essai sur le vrai NAS (D2), puis D1b (tâches de nuit, alertes ntfy, APK release et page d'installation, contrôles de sécurité) |
| 8 | Android TV | à faire, après le Polish |
| 10 | Lecteur web | à faire |

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
(Réalisé ensuite : streaming et progression en phase 5, métadonnées en 6, Android en 7 ; puis traitement média (phase 9, avancée avant la phase 8 le 2026-10-06), Android TV (8), lecteur web (10).)

## Phase 5 — Streaming définitif et progression (backend, testable avec curl)
- URL de lecture signée (HMAC, 6 h, liée au fichier et à l'utilisateur) et endpoint Range durci : ARCHITECTURE §6.
- Progression par utilisateur, « continuer à regarder », terminé au-delà de 90 % : ARCHITECTURE §6.3.
- Endpoint du spike `/api/dev/*` : **supprimé le 2026-10-08** (avec `android/spike`). Avant : conservé derrière son drapeau (`DEV_SPIKE_STREAM_ENABLED`, faux par défaut, absent de Docker Compose) tant que l'app Android du spike s'en sert. **À retirer** (code, tests, config, SPIKE.md §3) dès que l'app Android passe par `/api/episodes/{id}/stream-url` + `/api/stream/…`, au plus tard avec l'app Android complète. `ByteRange` et `VideoMediaTypes` restent (utilisés par le streaming définitif).
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
- AniList seule source, suites TV suivies jusqu'au nombre de saisons du dossier, 20 rôles au plus par animé, photos des comédiens sur le NAS (plus d'image de personnage depuis le 2026-10-05). ARCHITECTURE §18–19.

## Phase 7 — Application Android (téléphone)
- Kotlin, Compose, Media3 dans `android/app`, structure prête pour la TV. ARCHITECTURE §20, `android/README.md`.
- Livré : connexion (refresh token natif chiffré par le Keystore), accueil, bibliothèque (validés sur Galaxy S24 le 2026-10-05) ; fiche, distribution (comédiens seuls) et page comédien (validés le 2026-10-05) ; lecteur Media3 et progression. `android/spike` et `/api/dev/stream` supprimés le 2026-10-08 (fin de P3).

## Phase 9 — Traitement média (ffprobe, remux, sous-titres)
But : savoir ce que contient chaque fichier, et rendre lisibles sans ré-encodage ceux qui ne le sont que par leur conteneur. Prérequis du lecteur web (phase 10), utile aussi à l'app Android. **Le transcodage (ré-encodage vidéo ou audio) reste hors périmètre.**

Règles communes :
- `/media` reste en lecture seule ; rien n'y est jamais écrit. Tout résultat va dans un **dossier de cache à part** (volume dédié, ex. `${MEDIA_CACHE_PATH}:/cache`), nommé par identifiant en base, jamais d'après un nom venant du client.
- ffprobe et ffmpeg lancés par `ProcessBuilder` avec une **liste d'arguments fixe** (aucun shell, aucune chaîne construite par concaténation ; le seul argument variable est le chemin résolu depuis l'identifiant en base, précédé de `file:` pour qu'un nom de fichier ne soit jamais pris pour une option ou un protocole).
- Sur un NAS de 4 Go : un seul processus ffmpeg à la fois (ffprobe : 1 ou 2), priorité basse (`nice`/`ionice`), **délai maximal** par processus (puis arrêt forcé), **limite de mémoire** par processus (`prlimit --as`, ou un conteneur dédié avec `mem_limit` si c'est plus simple), sortie standard et erreur lues en continu et tronquées dans les logs.
- ffmpeg/ffprobe ajoutés à l'image du backend (environ +100 Mo) ; version affichée dans l'admin.

### 9.1 Analyse du catalogue (ffprobe) — étape 1, puis validation
1. ffmpeg et ffprobe dans l'image du backend, **version figée** (source officielle vérifiée par empreinte, compilation réduite aux conteneurs et codecs utiles) ; taille ajoutée documentée. Aucun shell : arguments fixes, chemin lu en base, délai maximal par fichier.
2. Tâche de fond séparée du scan : idempotente, reprenable, un fichier à la fois, priorité basse (`nice`/`ionice`), en pause pendant un scan ; seulement les fichiers nouveaux ou modifiés (taille, date).
3. Par fichier : durée, conteneur, vidéo (codec, profil, 10 bits, définition), pistes audio (langue, codec, canaux), sous-titres (langue, format, par défaut, forcé), résultat (échec avec raison).
4. Durée des épisodes renseignée (web et Android) ; la progression s'appuie sur elle quand le lecteur annonce une durée incohérente.
5. Classification documentée, règles explicites et modifiables, testées : Android « lisible directement » / « remux nécessaire » (AVI, OGM) / « transcodage nécessaire » (codec non décodable) ; navigateur « lisible » ou « non lisible » avec les raisons (MKV, HEVC 10 bits, MPEG-4 ASP, ASS/VobSub…).
6. Admin, onglet « Médias » : avancement, répartition, liste filtrable (sans analyse, par catégorie, échecs), espace estimé pour remuxer tous les AVI/OGM.
7. **Test à blanc du remux**, lancé à la main : chaque AVI/OGM remuxé vers une sortie nulle (rien n'est écrit), commandes « genpts » et « genpts + mpeg4_unpack_bframes », un fichier à la fois, arrêt et reprise, résultat par commande et raison des échecs ; démarrage différé possible (la nuit).
8. Estimations : durée du premier passage (~28 000 fichiers) et charge disque ; durée du test à blanc.
9. Tests : faux ffprobe (logique), vrai ffprobe sur petits fichiers générés, classification, reprise, fichiers modifiés, échec d'analyse, aucun chemin de fichier hors de l'API admin.

### 9.2 Remux à la demande (AVI et OGM, pour Android) — étape 2 (livrée, en attente de validation ; ARCHITECTURE §23)
Constat (2026-10-06, ARCHITECTURE §20.3 et §21) : AVI et OGM réels non lisibles sur Android, mais lisibles avec le son une fois remuxés en MKV sans ré-encodage (validé à la main sur le S24 : `-fflags +genpts -i entrée -c copy sortie.mkv` pour l'AVI Air Gear et l'OGM de test ; pour l'AVI, la variante avec `-bsf:v mpeg4_unpack_bframes` marche aussi). Environ 920 fichiers (~3 % du catalogue, plus de 150 Go). **Android uniquement** : le navigateur ne lira pas du MPEG-4 ASP, même remuxé.
1. API : `stream-url` d'un fichier à remuxer répond « préparation en cours » (statut distinct d'une erreur, délai avant nouvelle demande, progression si possible) ; une fois prêt, URL signée vers la copie en cache ; jamais d'URL vers l'original illisible.
2. Cache : `REMUX_CACHE_PATH` (volume en écriture, hors `/media` qui reste en lecture seule ; création et droits PUID/PGID comme pour les affiches), taille maximale `REMUX_CACHE_MAX_GB`, purge des copies les moins récemment lues (jamais une copie en cours de lecture), espace libre vérifié avant de commencer, nom par empreinte, fichier temporaire puis renommage atomique, nettoyage des restes au démarrage.
3. Exécution : commandes validées (AVI : genpts, et `mpeg4_unpack_bframes` en repli ou d'après le test à blanc ; OGM : genpts), `-c copy`, toutes les pistes, arguments fixes sans shell, délai maximal, un seul remux à la fois, deux demandes du même épisode partagent le travail. **Seul un code de sortie non nul est un échec** ; avertissements au journal.
4. Échecs : « remux impossible » avec la raison, nouvelles tentatives espacées (pas de boucle), rapport admin, « relancer ».
5. Admin : état du cache, remux en cours et file d'attente, échecs, « préparer à l'avance » pour un animé (plafonné), « vider le cache ».
6. Android : écran « Préparation de l'épisode… », nouvelles demandes espacées, annulation, message clair en cas d'échec ; scénario de test dans `android/README.md`.
7. Web : message « format non lisible dans un navigateur » (pas de lecteur web dans cette phase).
8. Tests : faux ffmpeg (succès, échec, délai, avertissement), concurrence, purge, atomicité, cache plein, signature sur la copie, path traversal, vrai ffmpeg sur petits fichiers.
9. README, ARCHITECTURE (volume, droits, taille conseillée), FUTURE (transcodage, remux préventif planifié).

### 9.3 Extraction des sous-titres (pour le lecteur web, avec la phase 10)
- Sous-titres texte muxés (ASS/SSA/SRT) extraits dans le cache : WebVTT (lu nativement par le navigateur, sans les styles ASS) et ASS d'origine conservé pour un éventuel moteur de rendu ASS côté navigateur (décision en phase 10, avec les polices jointes).
- Sous-titres image (PGS, VobSub) : non extractibles en texte ; les afficher dans un navigateur demanderait de les incruster, donc du transcodage → hors périmètre, signalés dans le rapport.
- À la demande, mêmes limites (délai, mémoire, cache borné) : l'extraction lit tout le fichier, son coût disque est proche de celui d'un remux.

## Phase Polish — interface web et Android (avant le déploiement sur le NAS et la phase 8)
Objectif : une interface évidente au premier regard, au niveau des meilleures applications de streaming, sans en copier les marques ni les visuels. Contraintes : aucun changement serveur, API ou sécurité sans accord ; tests verts ; aucune ressource externe au runtime (polices et icônes embarquées, licences listées) ; français ; accessibilité (contraste, texte agrandi, TalkBack, clavier, cibles de 48 px) ; tout ce qui est dessiné sur Android reste utilisable à la télécommande ; chaque dépendance nouvelle ou mise à jour majeure est proposée avant d'être installée.
- **P1 — audit et direction** (livrée, `docs/DESIGN.md`) : inventaire et parcours, audit, propositions par parcours, direction visuelle et guide de style (`docs/design/guide-de-style.html`), équivalent Compose, catalogue de démonstration (`scripts/demo-catalog.sh`), dépendances proposées, changements serveur à valider, plan de P2 et P3. Arrêt, décisions.
- **P2 — web** (livrée le 2026-10-07, bilan DESIGN §12) : composants communs, accueil, recherche et filtres, fiche, comédien, connexion et compte, admin ; téléphone d'abord ; mesures avant/après sur le catalogue de démonstration ; tests d'accessibilité automatiques ; captures avant/après. Arrêt.
- **P3 — Android** (livrée le 2026-10-08, bilan DESIGN §13) : thème, composants, accueil, bibliothèque, fiche, comédien, lecteur (commandes, Préparation, erreurs), connexion ; taille de texte système, mode sombre, télécommande ; tests et scénario manuel. Arrêt.

## Phase 8 — Android TV (après le Polish)
- Périmètre à préciser au démarrage de la phase. Base prévue : un paquet `ui/tv` (écrans et navigation propres) sur les mêmes ViewModels et la même couche `data` que le téléphone (ARCHITECTURE §20).

## Phase 10 — Lecteur web
Lecteur `<video>` dans l'interface web, sur l'URL signée existante, avec reprise et progression comme sur Android. Après la phase 9, dont il dépend. Périmètre réel :

- **(a) Lecture directe** des fichiers que le navigateur sait lire tels quels : MP4 avec vidéo H.264 et audio AAC (ou MP3). C'est le seul cas sûr partout (Chrome, Firefox, Edge).
- **(b) Sous-titres.** Un navigateur **ne lit pas les pistes de sous-titres embarquées dans un MKV** (ni dans un MP4) et **ne sait pas rendre l'ASS** (styles, positions, polices). Il faut donc **extraire les pistes côté serveur avec ffmpeg** (phase 9.3) et les servir à part : en **WebVTT** (piste `<track>`, lue nativement, styles ASS perdus) ou en **ASS d'origine** rendu côté navigateur par **JASSUB** (libass compilé en WebAssembly, avec les polices jointes du MKV ; à vérifier avec la CSP, qui devra autoriser le WebAssembly). Cela suppose **ffmpeg et ffprobe dans le conteneur backend**. Sous-titres image (PGS, VobSub) : non affichables sans incrustation, donc sans transcodage → message.
- **(c) Remux sans ré-encodage** des MKV (et AVI, OGM) dont les codecs conviennent, vers **MP4** (fichier en cache, phase 9.2) ou **HLS** (segments produits à la volée par ffmpeg en `-c copy`, lus par hls.js ; lecture possible avant la fin du remux, au prix de plus de travail côté serveur). Choix entre les deux au démarrage de la phase, selon le rapport ffprobe.
- **(d) HEVC (H.265), surtout 10 bits** : lecture très variable selon le navigateur et le matériel (souvent impossible, Firefox en particulier). **Pas de transcodage dans le MVP** : le lecteur affiche un **message clair** (« Ce fichier n'est pas lisible dans le navigateur : utilisez l'application Android ») pour tout fichier classé « transcodage nécessaire » (vidéo HEVC non supportée, audio AC3/DTS, sous-titres image).

**Sans l'extraction des sous-titres (b), un lecteur web serait peu utile** : la bibliothèque est entièrement en VOSTFR, et les sous-titres français sont presque toujours des pistes embarquées dans les MKV (souvent en ASS ; seuls 434 fichiers de sous-titres externes dans le relevé). Un lecteur qui n'afficherait que l'image et le son japonais ne servirait à presque personne ; (b) n'est donc pas une option mais le cœur de la phase.


## Phase D1 — Déploiement sur le NAS (plan validé le 2026-10-09)

Ami : 3 gestes (Container Manager, une commande dans le Planificateur de tâches, l'assistant) plus la redirection de
port faite avec Matthieu. Détails : `docs/DEPLOIEMENT.md`, aide-mémoire `docs/AIDE-DEPLOIEMENT-AMI.md`.

- **D1a** (livrée le 2026-10-09, essai de bout en bout `scripts/test/e2e-deploy.sh`, 55 vérifications) :
  D1.1 images et publication sans secret · D1.2 secrets et utilisateur par init · D1.3 assistant, deux portes, Caddy,
  DuckDNS, réglages · D1.4 invitations · D1.5 sauvegardes · D1.6 mises à jour avec retour arrière · D1.7 installateur,
  plan B Funnel, vérification d'accès, documentation.
- **1.0.1** (2026-10-10, après la répétition sur le PC de Matthieu) : installateur (plage réseau libre, accès au
  registre vérifié dès la simulation, `localhost` sous WSL, `--pull always`), affiche qui suit tout de suite une
  correction manuelle, recherche TMDB refaite après une correction AniList (sauf fiche TMDB verrouillée), distribution
  réveillée à la fin des métadonnées, une ligne de journal par animé traité, « en attente » expliqué dans
  l'administration, version affichée en bas à droite du site (ordinateur), section « Répétition sur ton PC ».
- **D2** : installation d'essai sur le DS923+ (liste « Ce qui exige le vrai NAS », DEPLOIEMENT §12).
- **D1b** (après D2) : planificateur interne (scan de nuit, remux préventif plafonné et en pause pendant les lectures),
  alertes ntfy et conteneur de veille, export de diagnostic, APK release (R8, version, keystore avec confirmation des
  deux copies), page « Installer l'application » avec QR code, proposition de mise à jour dans l'app, onglet Sécurité
  et `check-exposure`.
