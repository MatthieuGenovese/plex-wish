package fr.plexwish.animeserver.metadata;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Source de métadonnées d'animés (ARCHITECTURE §15). AniList est le premier fournisseur ; un autre (TMDB pour des
 * synopsis en français, par exemple) s'ajoutera derrière la même interface. Le fournisseur et la langue du synopsis
 * sont enregistrés avec chaque fiche.
 */
public interface MetadataProvider {

    /** Identifiant stocké en base ({@code anime.metadata_provider}), ex. "ANILIST". */
    String id();

    /** Nom affiché (attribution). */
    String displayName();

    /** Langue des synopsis de ce fournisseur (code ISO 639-1, ex. "en"). */
    String synopsisLanguage();

    /** Recherche par titre : candidats dans l'ordre de pertinence du fournisseur. */
    List<Candidate> search(String title) throws ProviderUnavailableException;

    /** Fiche par identifiant du fournisseur (correction manuelle). */
    Optional<Candidate> byId(String providerId) throws ProviderUnavailableException;

    /** Genres de fiches déjà connues (par lots, ARCHITECTURE §24.6) : identifiant → genres. */
    java.util.Map<String, List<String>> genres(List<String> providerIds) throws ProviderUnavailableException;

    /** Une fiche candidate, telle que renvoyée par le fournisseur. {@code genres} : genres du fournisseur (jamais null). */
    record Candidate(String providerId, String romaji, String english, String nativeTitle, List<String> synonyms,
                     Integer year, String format, Integer episodes, String synopsis,
                     String posterUrl, String posterLargeUrl, String siteUrl, List<String> genres) {

        /** Tous les titres comparables (le titre natif japonais n'est pas comparable à un nom de dossier). */
        public List<String> titles() {
            List<String> all = new java.util.ArrayList<>();
            if (romaji != null) all.add(romaji);
            if (english != null) all.add(english);
            if (synonyms != null) all.addAll(synonyms);
            return all;
        }

        /** Titre affiché : anglais s'il existe, sinon romaji. */
        public String displayTitle() {
            return english != null && !english.isBlank() ? english : romaji;
        }
    }

    /**
     * Fournisseur indisponible ou limite de débit atteinte : on réessaie plus tard, sans jamais bloquer
     * la bibliothèque. {@code retryAfter} : délai demandé par le fournisseur, s'il en a donné un.
     */
    class ProviderUnavailableException extends Exception {
        private final Duration retryAfter;

        public ProviderUnavailableException(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }
}
