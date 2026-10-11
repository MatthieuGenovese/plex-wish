package fr.plexwish.animeserver.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le journal ne garde pas les coupures normales des lecteurs vidéo, mais garde toute autre erreur. */
class ClientDisconnectLogFilterTest {

    private static LogRecord record(String logger, String message, Throwable thrown) {
        LogRecord r = new LogRecord(Level.SEVERE, message);
        r.setLoggerName(logger);
        r.setThrown(thrown);
        return r;
    }

    @Test
    void clientDisconnectsFromVertxAreDropped() {
        ClientDisconnectLogFilter f = new ClientDisconnectLogFilter();
        assertFalse(f.isLoggable(record("io.vertx.core.net.impl.ConnectionBase", "Connection reset by peer",
                new IOException("Connection reset by peer"))));
        assertFalse(f.isLoggable(record("io.vertx.core.net.impl.ConnectionBase", "Erreur", new IOException("Broken pipe"))));
    }

    @Test
    void everythingElseIsKept() {
        ClientDisconnectLogFilter f = new ClientDisconnectLogFilter();
        assertTrue(f.isLoggable(record("io.vertx.core.net.impl.ConnectionBase", "Connection refused", new IOException("Connection refused"))));
        assertTrue(f.isLoggable(record("fr.plexwish.animeserver.stream.StreamResource", "Connection reset by peer", null)));
        assertTrue(f.isLoggable(record(null, "x", null)));
    }
}
