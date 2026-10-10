# Idées pour plus tard

Hors scope de l'étape en cours. Rien ici n'est implémenté.

## Bibliothèque
- Choix entre plusieurs versions d'un même épisode (720p / 1080p) au lieu de « garder le premier et signaler l'autre ».
- Appliquer une correction manuelle immédiatement, sans attendre le scan suivant.
- Scan automatique périodique ou à la détection de changements (aujourd'hui : lancement manuel par l'admin).
- Interprétation automatique des doubles épisodes (`03-04`) et des numéros décimaux (`E05.5`).

## Lecture
- Épisode suivant enchaîné automatiquement à la fin d'un épisode (avec compte à rebours et « Annuler ») sur Android, puis sur le web.
- Rendu ASS fidèle sur Android (polices jointes, balises en ligne, « signs ») : libass via une extension native de Media3, ou sous-titres convertis côté serveur ; aujourd'hui rendu simplifié de Media3 (voir `android/README.md`).
- Transcodage (vidéo, ou audio seul AC3/DTS → AAC, bien moins coûteux) pour les fichiers classés « transcodage nécessaire » par la phase 9 (traitement média). Hors périmètre tant que le rapport n'a pas montré combien de fichiers sont concernés.
- Remux préventif planifié (la nuit) des AVI/OGM d'un animé en cours de visionnage, ou des épisodes suivants ; aujourd'hui à la demande, ou « Préparer l'animé » à la main.
- Lecture pendant le remux (sortie fragmentée lue au fil de l'eau) si l'attente de préparation gêne sur le NAS.
- ~~Extension ffmpeg de Media3 côté Android~~ : fait le 2026-10-10 avec NextLib (décodage logiciel en repli, `android/README.md`).
- Règles du serveur (`MediaRules`) pour le son : DTS et TrueHD sont désormais lus par l'app (FFmpeg), AC3/E-AC3 ne « dépendent » plus du téléphone ; à refléter dans la classification (aujourd'hui « transcodage nécessaire » / « dépend du téléphone ») après l'essai sur le S24. Changement de règle serveur : à valider.
- Décodage logiciel sur le web (phase 10) : les navigateurs n'ont pas d'équivalent simple (WebAssembly FFmpeg trop lourd) ; la conversion côté serveur reste la voie prévue.
- FFmpeg de NextLib compilé sans assembleur (`--disable-asm`) : si la fluidité déçoit en 1080p, compiler NextLib nous-mêmes avec les optimisations NEON (même licence).
- R8 (réduction du code) désactivé en release : s'il est activé un jour, les règles de NextLib suffisent pour les classes JNI (vérifié le 2026-10-10 dans `seeds.txt` : méthodes natives, `VideoDecoderOutputBuffer`, `growOutputBuffer` conservés).
- Rendu fidèle des sous-titres ASS (styles, positionnement) : ExoPlayer les affiche sans styles, les navigateurs pas du tout.
- Association des sous-titres externes (`.ass`, `.srt`, `.sup`) aux vidéos : même nom de base (24 cas sur 434), puis dossiers du type `sous-titres + police/` (avec les polices ASS à charger).
- Sous-titres image VobSub (et PGS) : à tester sur le téléphone ; les navigateurs ne les lisent pas du tout (incrustation ou OCR à étudier).

## Interface web
- Recherche tolérante à la ponctuation : « fate/apocrypha » ne trouve pas « Fate⁄Apocrypha » (barre de fraction dans le nom du dossier) ; aujourd'hui casse et accents seulement.
- Rapport de scan sur téléphone : cartes au lieu du tableau (aujourd'hui, défilement horizontal).
- « Continuer à regarder » sur l'accueil, avec la progression.

## Métadonnées
- **Synopsis TMDB par saison** (`tv/{id}/season/{n}`, en français) sur la fiche, quand une saison a le sien ; aujourd'hui, synopsis de la série.
- Si la recherche TMDB par titre déçoit : pont d'identifiants AniList → TMDB (liste Kometa Anime-IDs, licence MIT), voir ARCHITECTURE §16.1.
- Réapparier TMDB automatiquement quand l'appariement AniList d'un animé est corrigé (aujourd'hui : « Relancer » dans l'onglet Synopsis français).
- Affiches : plusieurs tailles (vignette légère pour la grille, w500 pour la fiche) si la bibliothèque paraît lente sur téléphone ; aujourd'hui une seule taille (~100 Ko).
- CSP `img-src 'self'` stricte une fois toutes les affiches locales (miniatures de l'admin AniList / TMDB comprises).
- Affiche et synopsis **par saison** (AniList a une fiche par saison) et titres d'épisodes.
- **Recherche de comédien** (et de personnage) dans la bibliothèque.
- **Doubleurs français** (langue déjà dans le modèle ; même requête AniList avec `language: FRENCH`), avec un choix VF / VOSTFR.
- Fusion manuelle de deux fiches « comédien » si AniList en a en double (rare).
- Rafraîchissement périodique des fiches appariées (synopsis complétés, nouvelles affiches) : aujourd'hui, uniquement via « Relancer » dans l'admin.

## Interface (après la phase Polish, voir docs/DESIGN.md)
- « Mes animés » : liste personnelle (ajouter / retirer), en plus de « Continuer à regarder ».
- Vignettes d'épisodes extraites par ffmpeg sur le NAS (charge et stockage à mesurer).
- Connexion de l'app par QR code ou lien d'invitation (adresse du serveur et identifiant préremplis).
- Saut automatique à l'épisode suivant (voir « Lecture ») : en fin d'épisode, carte « Épisode suivant » avec un compte à rebours de 10 s annulable, et bouton dans la surcouche du lecteur (proposé en P3, pas encore validé).
- « Vu » plus juste qu'au-delà de 90 % : temps restant (ex. moins de 3 min, plancher à 95 % pour les épisodes courts) ou chapitre « ED / Ending » des MKV lu par ffprobe (changement serveur).
- Mise à jour de Media3 (1.5.1 → récente) et commandes du lecteur en Compose (`media3-ui-compose`), avec un nouveau test complet sur le S24 (AVI, OGM, sous-titres).


## Déploiement (D1, 2026-10-09)

- Accès direct à la maison sans passer par Internet ni par la boucle locale de la box (HTTPS local : DNS local ou
  certificat pour l'adresse locale).
- IPv6 dans DuckDNS (paramètre `ipv6`) pour les box qui ont une IPv6 publique ; aujourd'hui IPv4 seulement.
- Autres DNS dynamiques au choix (deSEC, dynv6) si DuckDNS devient indisponible.
- Vraie adresse des visiteurs derrière Funnel (en-tête à vérifier) pour l'anti brute force.
- QR code du lien d'invitation (avec la bibliothèque QR de la page « Installer l'application », D1b).
- Copie automatique des sauvegardes hors du NAS (aujourd'hui : Hyper Backup de l'ami ou récupération manuelle).
