# Idées pour plus tard

Hors scope de l'étape en cours. Rien ici n'est implémenté.

## Bibliothèque
- Choix entre plusieurs versions d'un même épisode (720p / 1080p) au lieu de « garder le premier et signaler l'autre ».
- Appliquer une correction manuelle immédiatement, sans attendre le scan suivant.
- Scan automatique périodique ou à la détection de changements (aujourd'hui : lancement manuel par l'admin).
- Interprétation automatique des doubles épisodes (`03-04`) et des numéros décimaux (`E05.5`).
- ffprobe (codecs, durée, pistes audio/sous-titres) en tâche de fond après le scan, ou à la demande depuis la fiche d'un épisode. Jamais pendant le scan.

## Lecture
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
- **Télécharger les affiches sur le NAS** (cache local servi par nginx) : les navigateurs n'appelleraient plus le CDN d'AniList (adresse IP des spectateurs, disponibilité, CSP `img-src 'self'`).
- Affiche et synopsis **par saison** (AniList a une fiche par saison) et titres d'épisodes.
- Rafraîchissement périodique des fiches appariées (synopsis complétés, nouvelles affiches) : aujourd'hui, uniquement via « Relancer » dans l'admin.

## Polish de l'interface web
Remarques de design du propriétaire du projet, à traiter ensemble dans une passe dédiée (les design tokens de `web/src/styles.scss` permettent de changer couleurs, espacements et typographie sans toucher aux composants).
- *(à compléter)*
