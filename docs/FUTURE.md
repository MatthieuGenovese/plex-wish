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
- Extension ffmpeg de Media3 côté Android si le spike montre des pistes audio AC3/DTS non décodées.
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
- Saut automatique à l'épisode suivant (voir « Lecture »).
- Mise à jour de Media3 (1.5.1 → récente) et commandes du lecteur en Compose (`media3-ui-compose`), avec un nouveau test complet sur le S24 (AVI, OGM, sous-titres).
