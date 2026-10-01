package fr.plexwish.animeserver.library.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Lancement des scans : asynchrone, un seul à la fois (index unique partiel sur scan_run.status = RUNNING),
 * et remise en FAILED des scans restés RUNNING après un arrêt du conteneur (§7.1).
 */
@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);
    static final String INTERRUPTED = "interrompu par un redémarrage";

    @Inject
    AgroalDataSource dataSource;
    @Inject
    LibraryScanner scanner;
    @Inject
    ManagedExecutor executor;
    @Inject
    ObjectMapper json;

    void onStart(@Observes StartupEvent event) throws SQLException {
        String jnu = System.getProperty("sun.jnu.encoding", "?");
        if (!"UTF-8".equalsIgnoreCase(jnu)) {
            // Sous Linux, les noms de fichiers non ASCII seraient illisibles : lancer la JVM avec LANG=C.UTF-8.
            LOG.warnf("Encodage des noms de fichiers : %s (UTF-8 attendu). Les noms accentués ou japonais "
                    + "ne seront pas lus correctement : définir LANG=C.UTF-8", jnu);
        }
        int orphans = failOrphans();
        if (orphans > 0) {
            LOG.warnf("%d scan(s) resté(s) RUNNING après un arrêt : passé(s) en FAILED", orphans);
        }
    }

    int failOrphans() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "UPDATE scan_run SET status = 'FAILED', finished_at = now(), failure_reason = ?, failure_code = 'INTERRUPTED' WHERE status = 'RUNNING'")) {
            st.setString(1, INTERRUPTED);
            return st.executeUpdate();
        }
    }

    /** Crée le scan_run et lance le scan en tâche de fond ; 409 si un scan tourne déjà. */
    public long start(String triggeredBy, boolean confirmMassRemoval) {
        long runId;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "INSERT INTO scan_run (status, triggered_by) VALUES ('RUNNING', ?) RETURNING id")) {
            st.setString(1, triggeredBy);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                runId = rs.getLong(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApiException(409, "SCAN_ALREADY_RUNNING", "Un scan est déjà en cours");
            }
            throw new IllegalStateException(e);
        }
        LOG.infof("Scan %d lancé par '%s'%s", runId, triggeredBy,
                confirmMassRemoval ? " (disparition massive confirmée)" : "");
        executor.runAsync(() -> run(runId, confirmMassRemoval));
        return runId;
    }

    void run(long runId, boolean confirmMassRemoval) {
        try {
            ScanStats stats = scanner.scan(runId, confirmMassRemoval);
            finish(runId, "SUCCESS", stats, null, null);
            LOG.infof("Scan %d terminé en %d ms : %d vidéos, %d épisodes, %d extras, %d non résolues, %d doublons, %d disparues",
                    runId, stats.durationMs, stats.videos, stats.episodes, stats.extras, stats.unresolved,
                    stats.duplicates, stats.missing);
        } catch (LibraryScanner.ScanAbortedException e) {
            LOG.warnf("Scan %d abandonné : %s", runId, e.getMessage());
            finish(runId, "FAILED", null, e.code, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Scan %d en échec", runId);
            finish(runId, "FAILED", null, "INTERNAL_ERROR", "erreur interne (voir les logs du serveur)");
        }
    }

    private void finish(long runId, String status, ScanStats stats, String code, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "UPDATE scan_run SET status = ?, finished_at = now(), stats = ?::jsonb, failure_code = ?, failure_reason = ? WHERE id = ?")) {
            st.setString(1, status);
            st.setString(2, stats == null ? null : json.writeValueAsString(stats));
            st.setString(3, code);
            st.setString(4, reason);
            st.setLong(5, runId);
            st.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            LOG.errorf(e, "Impossible d'enregistrer la fin du scan %d", runId);
        }
    }
}
