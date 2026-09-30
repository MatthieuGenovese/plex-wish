package fr.plexwish.animeserver.library.parse;

/**
 * Transforme un chemin de vidéo, relatif à la racine média et séparé par des "/", en {@link ParseResult}.
 * Ne touche jamais au disque : il ne reçoit que des chaînes (testé sur library-sample.txt).
 */
public interface FilenameParser {

    ParseResult parse(String relativePath);
}
