package fr.plexwish.animeserver.library.scan;

import java.util.Map;
import java.util.TreeMap;

/** Compteurs d'un scan, stockés en JSON dans scan_run.stats (résumé par catégorie du rapport, §7.6). */
public class ScanStats {

    /** Vidéos vues pendant ce scan. */
    public int videos;
    /** Vidéos rattachées à un épisode (nouveau ou déjà connu). */
    public int episodes;
    public int extras;
    /** Aucun numéro d'épisode, ou vidéo hors dossier d'animé. */
    public int unresolved;
    public int duplicates;
    public int multiEpisodes;
    public int decimalEpisodes;
    /** Dossier de saison qui contredit le nom (importé selon le nom). */
    public int seasonMismatches;
    public int overridesApplied;
    public int ignoredByOverride;
    /** Fichiers jamais vus auparavant. */
    public int newFiles;
    /** Fichiers connus absents de ce scan (marqués indisponibles, jamais supprimés). */
    public int missing;
    /** Épisodes rebranchés sur un nouveau fichier (l'ancien a disparu). */
    public int rebranched;
    public int symlinksSkipped;
    public int unreadable;
    /** Autres fichiers, comptés par type (sous-titres externes compris, §7.8). */
    public Map<String, Integer> otherFiles = new TreeMap<>();
    /** Fichiers disponibles avant ce scan. */
    public int knownFiles;
    /** Plus de la moitié des fichiers connus ont disparu, et l'admin l'a confirmé. */
    public boolean massRemovalConfirmed;
    public int animeCount;
    public long durationMs;

    void countOther(String type) {
        otherFiles.merge(type, 1, Integer::sum);
    }
}
