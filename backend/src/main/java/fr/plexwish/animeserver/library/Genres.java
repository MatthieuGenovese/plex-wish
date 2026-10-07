package fr.plexwish.animeserver.library;

import java.util.Map;

/**
 * Libellés français des genres AniList (ARCHITECTURE §24.6). La base garde la valeur d'AniList (« Slice of Life ») ;
 * un genre inconnu (ajouté plus tard par AniList) garde son nom anglais.
 */
public final class Genres {

    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("Action", "Action"),
            Map.entry("Adventure", "Aventure"),
            Map.entry("Comedy", "Comédie"),
            Map.entry("Drama", "Drame"),
            Map.entry("Ecchi", "Ecchi"),
            Map.entry("Fantasy", "Fantasy"),
            Map.entry("Hentai", "Hentai"),
            Map.entry("Horror", "Horreur"),
            Map.entry("Mahou Shoujo", "Magical girl"),
            Map.entry("Mecha", "Mecha"),
            Map.entry("Music", "Musique"),
            Map.entry("Mystery", "Mystère"),
            Map.entry("Psychological", "Psychologique"),
            Map.entry("Romance", "Romance"),
            Map.entry("Sci-Fi", "Science-fiction"),
            Map.entry("Slice of Life", "Tranche de vie"),
            Map.entry("Sports", "Sport"),
            Map.entry("Supernatural", "Surnaturel"),
            Map.entry("Thriller", "Thriller"));

    private Genres() {
    }

    public static String label(String genre) {
        return LABELS.getOrDefault(genre, genre);
    }
}
