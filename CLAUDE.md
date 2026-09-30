Anime Server : serveur de streaming d'animés auto-hébergé

Projet privé pour un petit groupe (~10 utilisateurs), destiné à tourner sur un NAS Synology DS923+ via Docker. Il remplace à terme l'usage de Plex pour l'anime. Ce n'est PAS un clone de Plex.

Ce document contient les règles stables du projet et le scope de la première étape. Le reste (Android, TV, etc.) sera traité plus tard et est volontairement absent.

Contexte important
Le développeur a ~10 ans d'expérience en dev, mais aucune compétence en vidéo (codecs, conteneurs, sous-titres). Explique brièvement tes choix vidéo et signale les risques de compatibilité au lieu de les supposer connus.
Les fichiers vidéo appartiennent à un tiers (le propriétaire du NAS) et existent déjà. Leur nommage réel n'est pas encore connu : ne pas supposer un format unique (voir section Bibliothèque).
Les utilisateurs regardent surtout sur téléphone Android, parfois sur navigateur. Pas d'Apple. Les TV (Samsung/LG/Android TV) sont hors scope pour l'instant.
Scope de cette étape

Inclus :

spike vidéo (Range Requests + lecteur Android minimal) pour valider la faisabilité ;
monorepo, Docker Compose, PostgreSQL, Quarkus, Angular ;
authentification, utilisateurs, rôles, admin ;
scan du dossier média et modèle Anime / Season / Episode ;
interface web : login, bibliothèque, fiche anime, admin.

Exclu pour l'instant (à noter dans docs/FUTURE.md si le sujet revient, sans l'implémenter) : application Android complète, Android TV, Samsung Tizen, LG webOS, iOS, Chromecast, offline, transcodage, sous-titres, métadonnées AniList, progression / "continuer à regarder", recommandations, MyAnimeList, multi-serveurs, microservices, plugins.

Stack imposée
Backend : Java 21, Quarkus, Maven, REST JSON, Hibernate ORM with Panache, PostgreSQL, JWT, OpenAPI/Swagger, Flyway, JUnit.
Web : Angular, TypeScript, standalone components, Angular Router, HttpClient, responsive, thème sombre, pas de bibliothèque UI lourde sans justification forte.
Infra : Docker, Docker Compose, PostgreSQL en conteneur, backend en conteneur, frontend servi par nginx, configuration par variables d'environnement.

Monorepo :

text
anime-server/
├── backend/
├── web/
├── android/        (créé seulement pour le spike, voir phase 0)
├── docs/
├── docker-compose.yml
├── .env.example
└── README.md

Le backend est la source de vérité. Aucune logique métier dupliquée côté client.

Règles médias (non négociables)
Le dossier média est monté en lecture seule dans le conteneur (${MEDIA_PATH}:/media:ro). Ne jamais hardcoder /volume1/animes, le chemin hôte vient de .env.
L'application ne supprime, ne renomme, ne déplace ni ne modifie jamais un fichier média.
Toutes les données applicatives vivent en base.
Un client ne fournit jamais un chemin fichier : l'accès se fait uniquement via des IDs connus en base. Protection path traversal obligatoire, avec test.
En développement local, dossier média de test : ./dev-media.
Bibliothèque

Le backend scanne récursivement /media. La vraie bibliothèque est connue (relevé du 2026-09-30 : 28 254 vidéos, 1 317 animés, un dossier de premier niveau par animé). Le relevé brut est dans docs/private/ (contient un nom de personne : jamais versionné). Jeu de test dérivé : backend/src/test/resources/library-sample.txt (chemins relatifs, un par ligne). Détails et exemples : docs/ARCHITECTURE.md §7.

Règles :

1. Le nom du fichier est la source de vérité. Le dossier de saison manque pour environ trois quarts des vidéos (21 666 sur 28 254) et contredit parfois le fichier : il sert de secours et de vérification, jamais de condition d'import. Un désaccord va dans le rapport de scan. Le titre de l'animé vient du dossier de premier niveau.
2. Parsing derrière une interface FilenameParser, dans l'ordre : SxxExx ; NxEE (casse indifférente, saison sur 1 ou 2 chiffres : 2x06, 01X01) ; E\d+ seul (saison prise dans le dossier) ; puis test d'extras (règle 4) ; puis numéro seul (numérotation absolue acceptée) = le DERNIER nombre de 2 à 4 chiffres précédé d'un espace ou d'un underscore, après retrait des crochets/parenthèses et des éléments techniques (1080p, x264, HEVC, 10bits, AAC…). Exemples : "Slime 300 S1 - 01" → 1 ; "[Kaerizaki-Fansub]_One_Piece_Fish_Man_Island_01_[...]" → 1 ; "Naruto Shippuden Kai 113 - Hebi" → 113. Règle exacte : ARCHITECTURE §7.2. Sans indication de saison : saison 1.
3. Saison 0 = "Spéciaux" (S00Exx, 0xNN, dossiers OAV / Special / Bonus). Un fichier qui porte un numéro d'épisode reste un épisode, même si son dossier ou son nom contient "OAV", "Bonus" ou "Extra".
4. Extras (exclus de la liste des épisodes) : uniquement les fichiers SANS numéro d'épisode (NCOP/NCED, OP/ED, menus BD, AMV, trailers, dossiers de musique OST). Le test d'extras passe APRÈS SxxExx, NxEE et E\d+, et AVANT la stratégie numéro seul uniquement : un S00Exx dans un dossier OAV/Bonus reste un épisode de la saison Spéciaux.
5. Doublons d'épisode (même animé, saison, épisode) : garder un fichier, signaler l'autre. Doubles épisodes ("03-04") et numéros décimaux ("E05.5", "0.89") : signalés, correction manuelle.
6. Rapport de scan : résumé par catégorie (reconnus, extras, non résolus, doublons, désaccords dossier/fichier) et liste filtrable.
7. Correction manuelle par l'admin (fichier → animé, saison, épisode), stockée en base, jamais écrasée par un rescan.
8. Sous-titres externes (.ass, .sup, .srt) : comptés, pas associés dans le MVP. La lecture s'appuie sur les sous-titres muxés dans la vidéo.
9. Performance : scan par lots (jamais une requête par fichier), ffprobe hors du scan (à la demande ou tâche séparée). Le scan ne suit pas les liens symboliques qui sortent de /media.
10. Tests du parser sur library-sample.txt, sur des chaînes (jamais de vrais fichiers : certains noms sont interdits sous Windows) : au moins 97 % d'épisodes reconnus, plus un test par piège (Slime 300 S1 - 01 ; Genshiken 01X01 ; AH! My Goddess E12 ; S00E18 dans un dossier OAV ; Devilman Crybaby en doublon ; One_Piece_Fish_Man_Island_01_ ; Naruto Shippuden Kai 113 - Hebi ; liste complète : ARCHITECTURE §7.11).

Les fichiers non reconnus ne font jamais échouer le scan.

Concepts : Anime, Season, Episode, MediaFile.

Anime : id, title, alternativeTitle, synopsis, posterUrl, year, metadataProviderId (les 4 derniers restent vides à cette étape)
Season : id, animeId, seasonNumber
Episode : id, seasonId, episodeNumber, title, synopsis, duration, mediaFileId
MediaFile : id, relativePath (chemin relatif à /media, décision ARCHITECTURE §4.2), fileName, fileSize, container (codecs et durée : plus tard, via ffprobe)

Le rescan est idempotent : aucun doublon, détection des fichiers ajoutés, et marquage (sans suppression physique) des fichiers disparus. Test obligatoire.

Authentification et sécurité

Le projet sera exposé à Internet derrière un reverse proxy qui termine le HTTPS (pas de HTTPS dans Quarkus).

User, rôles ADMIN et USER, login par username/email + mot de passe.
Mots de passe hashés (bcrypt ou argon2), jamais loggés.
JWT à durée de vie courte + refresh token révocable (stocké hashé en base). Pas de sur-ingénierie au-delà.
Rate limiting / protection brute force sur le login (simple, en mémoire ou en base, suffisant pour ce contexte).
Admin : créer et désactiver des utilisateurs. Pas d'inscription publique.
Admin initial créé au premier lancement via INITIAL_ADMIN_USERNAME et INITIAL_ADMIN_PASSWORD.
Côté Angular : ne pas stocker le JWT d'accès dans localStorage (risque XSS). Préférer un access token en mémoire + refresh token en cookie HttpOnly; Secure; SameSite. Documenter le choix retenu dans ARCHITECTURE.md.
CORS restrictif, validation des entrées, secrets uniquement par variables d'environnement, logs sans mots de passe ni tokens.
Aucun accès aux médias sans authentification (sauf l'endpoint de spike en dev, voir phase 0).

Décision à trancher dans ARCHITECTURE.md (phase 1) : une balise <video> ne peut pas envoyer de header Authorization. Le futur endpoint de streaming devra donc utiliser une URL signée à courte durée de vie (recommandé), ou un cookie. Ne pas implémenter le streaming définitif maintenant, mais choisir le mécanisme dès l'architecture pour ne pas avoir à tout reprendre.

API (à cette étape)

Noms améliorables :

text
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout

GET  /api/anime
GET  /api/anime/{id}
GET  /api/anime/{id}/seasons
GET  /api/seasons/{id}/episodes
GET  /api/episodes/{id}

POST /api/admin/library/scan
GET  /api/admin/library/scan-report
GET  /api/admin/users
POST /api/admin/users
PATCH /api/admin/users/{id}

Documentée avec OpenAPI.

Interface web (à cette étape)

Pages : /login, / (récemment ajoutés + bibliothèque), /anime, /anime/:id, /admin (utilisateurs, lancement du scan, rapport de scan).

Propre, sombre, lisible, responsive. Ne pas imiter visuellement Plex. Concevoir dès maintenant avec une navigation clavier correcte (focus visible, éléments larges), c'est presque gratuit et ça prépare un usage sur navigateur de TV.

Pas de lecteur vidéo web à cette étape.

Docker

docker-compose.yml utilisable sur Synology (Container Manager) : backend, web (nginx), postgres. Configuration par .env (.env.example fourni), dont MEDIA_PATH. Le README explique : prérequis, lancement backend, lancement Angular, lancement Docker complet, dossier média, création de l'admin, scan, accès à Swagger.

Méthode de travail

Avant d'écrire beaucoup de code :

inspecter le repository ;
créer docs/ARCHITECTURE.md (architecture concrète, décisions techniques, choix du mécanisme d'auth du streaming) ;
créer docs/ROADMAP.md ;
t'arrêter et attendre ma validation de ARCHITECTURE.md avant d'implémenter.

Ensuite :

Une phase à la fois. Fin de phase = tests verts + commit + court résumé + attente de ma validation avant la phase suivante.
Le projet doit rester compilable à chaque étape.
Me demander confirmation uniquement pour les décisions structurantes ou bloquantes ; sinon choisir la solution la plus simple et documenter le choix.
Toute amélioration non essentielle va dans docs/FUTURE.md.
Ne pas : refaire du code fonctionnel pour l'esthétique, multiplier les abstractions, optimiser prématurément, écrire de la documentation redondante.

Règle finale : serveur privé pour quelques amis sur un NAS, pas une plateforme pour des millions d'utilisateurs. Entre simplicité et sophistication, choisir la simplicité tant que la sécurité n'est pas compromise.

Phases
Phase 0 : spike vidéo (jetable, ~une demi-journée)

But : savoir si la lecture de vraies vidéos fonctionne sur un téléphone Android, avant d'investir dans le reste. Le code du spike est jetable, mais l'endpoint Range sera réutilisé et raffiné plus tard.

Backend Quarkus minimal avec un endpoint qui sert un fichier de dev-media avec les HTTP Range Requests : Range, Content-Range, Accept-Ranges, réponses 206, type MIME correct, streaming depuis le disque sans jamais charger le fichier en mémoire.
Endpoint désactivé par défaut, activé par une variable d'environnement de dev uniquement.
Le fichier est désigné par un identifiant simple ou un nom résolu dans dev-media, avec protection path traversal. Aucun chemin arbitraire.
Test automatisé des Range Requests (début, milieu, fin, plage invalide).
Mini app Android (android/spike/, Kotlin + Media3/ExoPlayer) : un écran, une URL configurable, un lecteur plein écran. Rien d'autre.
Documenter dans docs/SPIKE.md comment lancer le tout et comment tester avec 5 à 10 vrais fichiers (à mettre dans dev-media), avec une grille de résultats à remplir : fichier, conteneur, codec vidéo, codec audio, image OK, son OK, seek OK, remarques.

Sortie attendue : le résultat du test sur les vrais fichiers décide de la suite (Direct Play suffisant, fallback ffmpeg nécessaire, ou remise en question). Ne pas construire de transcodage.

Phase 1 : socle

Structure du monorepo, Docker Compose, PostgreSQL, Quarkus, Angular, Flyway avec schéma initial, ARCHITECTURE.md et ROADMAP.md (validation demandée).

Phase 2 : authentification

Users, rôles, login, refresh, rate limiting, admin initial, gestion des utilisateurs par l'admin. Tests : auth, permissions, brute force.

Phase 3 : bibliothèque

Scan de /media, parsing des noms, Anime / Season / Episode / MediaFile, rapport de scan, endpoints de lecture. Tests : parsing, idempotence du rescan, permissions.

Phase 4 : interface web

Login, accueil, bibliothèque, fiche anime, admin. Quelques tests utiles seulement.

Fin de l'étape. Bilan et décision ensemble avant d'aborder le streaming définitif, le lecteur web, la progression, les métadonnées puis Android complet.