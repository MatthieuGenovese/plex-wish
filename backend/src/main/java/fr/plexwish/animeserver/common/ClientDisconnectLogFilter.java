package fr.plexwish.animeserver.common;

import io.quarkus.logging.LoggingFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * Masque le bruit « Connection reset by peer » / « Broken pipe » de Vert.x : un lecteur vidéo (Android, navigateur)
 * qui ferme sa connexion pendant l'envoi d'un morceau (seek, pause, appli en arrière-plan, épisode quitté). C'est le
 * fonctionnement normal du streaming par plages ; Vert.x le journalise pourtant en ERROR avec une pile complète.
 * Seuls ces messages-là, venus de Vert.x / Netty, sont filtrés : toute autre erreur reste dans le journal.
 */
@LoggingFilter(name = "client-disconnect")
public final class ClientDisconnectLogFilter implements Filter {

    @Override
    public boolean isLoggable(LogRecord record) {
        String logger = record.getLoggerName();
        if (logger == null || !(logger.startsWith("io.vertx.") || logger.startsWith("io.netty."))) {
            return true;
        }
        return !(clientGone(record.getMessage()) || record.getThrown() instanceof IOException io && clientGone(io.getMessage()));
    }

    static boolean clientGone(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("connection reset by peer") || m.contains("broken pipe");
    }
}
