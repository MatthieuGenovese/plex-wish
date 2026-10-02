package fr.plexwish.animeserver.library.scan;

/** Événement CDI : un scan s'est terminé avec succès (de nouveaux animés peuvent attendre leurs métadonnées). */
public record ScanCompleted(long scanId) {
}
