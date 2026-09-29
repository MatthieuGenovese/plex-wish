# Roadmap — étape 1

Règles communes à chaque phase : projet compilable, tests verts, un commit, un court résumé, **puis attente de validation** avant la phase suivante.
Toute idée non essentielle va dans `docs/FUTURE.md`.

| # | Phase | Statut |
|---|---|---|
| — | Documents d'architecture | ✅ validés le 2026-09-29 |
| 0 | Spike vidéo | ✅ validé le 2026-09-29 sur fichiers synthétiques (Galaxy S24) — vrais fichiers, téléphone du propriétaire et sous-titres encore à tester (voir SPIKE.md §8) |
| 1 | Socle | 🔧 livrée — en attente de validation |
| 2 | Authentification | à faire |
| 3 | Bibliothèque | à faire |
| 4 | Interface web | à faire |

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
- Flyway `V2__library.sql` : anime, season, episode, media_file, scan_run, scan_issue.
- `FilenameParser` + `SeasonFolderParser` ; scan asynchrone, un seul à la fois ; rapport de scan.
- Endpoints de lecture `/api/anime…`, `/api/seasons…`, `/api/episodes…` ; endpoints admin de scan.
- Tests : parsing (cas propres, variantes de casse, incohérences, fichiers non vidéo), idempotence (rescan sans doublon, ajout, disparition, réapparition), path traversal, permissions.

## Phase 4 — Interface web
- Login, accueil (récemment ajoutés + bibliothèque), liste des animes, fiche anime (saisons/épisodes), admin (utilisateurs, lancement du scan, rapport).
- Token en mémoire, refresh au démarrage, interceptor, guards.
- Responsive, sombre, navigation clavier soignée. Pas de lecteur vidéo.
- Quelques tests : AuthService, interceptor, guards.

## Fin de l'étape
Bilan ensemble, puis dans l'ordre prévu : streaming définitif (URL signées), lecteur web, progression, métadonnées, Android complet.
