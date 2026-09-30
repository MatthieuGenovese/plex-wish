# Idées pour plus tard

Hors scope de l'étape en cours. Rien ici n'est implémenté.

## Bibliothèque
- Choix entre plusieurs versions d'un même épisode (720p / 1080p) au lieu de « garder le premier et signaler l'autre ».
- Garde-fou « disparition massive » : si un rescan ferait disparaître plus de X % des fichiers connus, ne rien marquer et demander confirmation à l'admin (cas d'un sous-dossier non monté, ou d'un mauvais dossier monté : constaté en phase 3 en oubliant le fichier compose de test, tout a été marqué disparu puis rétabli au scan suivant).
- Appliquer une correction manuelle immédiatement, sans attendre le scan suivant.
- Scan automatique périodique ou à la détection de changements (aujourd'hui : lancement manuel par l'admin).
- Interprétation automatique des doubles épisodes (`03-04`) et des numéros décimaux (`E05.5`).
- ffprobe (codecs, durée, pistes audio/sous-titres) en tâche de fond après le scan, ou à la demande depuis la fiche d'un épisode. Jamais pendant le scan.

## Lecture
- Extension ffmpeg de Media3 côté Android si le spike montre des pistes audio AC3/DTS non décodées.
- Rendu fidèle des sous-titres ASS (styles, positionnement) : ExoPlayer les affiche sans styles, les navigateurs pas du tout.
- Association des sous-titres externes (`.ass`, `.srt`, `.sup`) aux vidéos : même nom de base (24 cas sur 434), puis dossiers du type `sous-titres + police/` (avec les polices ASS à charger).
- Sous-titres image VobSub (et PGS) : à tester sur le téléphone ; les navigateurs ne les lisent pas du tout (incrustation ou OCR à étudier).
