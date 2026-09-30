package fr.plexwish.animeserver.library.parse;

/** Résultat du parsing d'un chemin de vidéo (ARCHITECTURE §7.2–7.5). */
public sealed interface ParseResult permits ParseResult.Episode, ParseResult.Extra, ParseResult.Unresolved {

    /** Titre de l'animé = dossier de premier niveau (null si le fichier est à la racine). */
    String animeTitle();

    /** Stratégie qui a reconnu l'épisode, dans l'ordre d'évaluation. */
    enum Strategy { SXXEXX, NXEE, E_NUMBER, NUMBER_ONLY, OVERRIDE }

    /**
     * Épisode reconnu.
     *
     * @param folderSeasonConflict saison du dossier quand elle contredit celle du nom (sinon null) :
     *                             le nom l'emporte, le désaccord va au rapport.
     */
    record Episode(String animeTitle, int season, int episode, Strategy strategy, Integer folderSeasonConflict)
            implements ParseResult {
    }

    /** Vidéo sans numéro d'épisode reconnue comme générique, menu, trailer… (§7.4). */
    record Extra(String animeTitle, String marker) implements ParseResult {
    }

    enum Problem {
        /** Aucune stratégie ne donne de numéro, aucun marqueur d'extra. */
        NO_EPISODE_NUMBER,
        /** Double épisode ("03-04", "S01E03-E04") : correction manuelle. */
        MULTI_EPISODE,
        /** Numéro décimal ("E05.5", "0.89") : correction manuelle. */
        DECIMAL_EPISODE,
        /** Vidéo posée directement à la racine : pas de dossier d'animé. */
        NO_ANIME_FOLDER
    }

    record Unresolved(String animeTitle, Problem problem, String detail) implements ParseResult {
    }
}
