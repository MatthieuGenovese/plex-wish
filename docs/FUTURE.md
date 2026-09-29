# Idées pour plus tard

Hors scope de l'étape en cours. Rien ici n'est implémenté.

## Bibliothèque
- `ReleaseGroupAbsoluteParser` : `[Groupe] Titre - 05 [1080p].mkv` (numérotation absolue).
- Dossier `Specials` / `Season 00`, fichiers à plat sans dossier de saison.
- Choix entre plusieurs versions d'un même épisode (720p / 1080p).
- Garde-fou « disparition massive » : si un rescan ferait disparaître plus de X % des fichiers connus, ne rien marquer et demander confirmation à l'admin (cas d'un sous-dossier non monté).

## Lecture
- Extension ffmpeg de Media3 côté Android si le spike montre des pistes audio AC3/DTS non décodées.
- Rendu fidèle des sous-titres ASS (styles, positionnement) : ExoPlayer les affiche sans styles, les navigateurs pas du tout.

## Outillage
- Maven Wrapper (`mvnw`) pour ne pas imposer Maven installé.
