package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.progress.UpNextService;
import fr.plexwish.animeserver.setup.AppSettings;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Planificateur interne minimal (décision D11, docs/WEB-PLAYER.md §4.3, §5) : une fois par nuit, dans la fenêtre de
 * nuit (1 h - 7 h par défaut, heure du serveur) :
 * <ol>
 *     <li>nettoyage du cache web : préparations dont la source a disparu ou changé, puis les moins récemment lues
 *     jusqu'à repasser sous 85 % du plafond (jamais une lue récemment, jamais un travail en cours) ;</li>
 *     <li>préparation préventive (si activée) : les 2 épisodes suivants de chaque animé regardé depuis 30 jours (tous
 *     comptes), puis les épisodes ajoutés depuis 7 jours. Préparation de base, et conversion du son ; conversion de
 *     la vidéo seulement si le réglage le permet (D8).</li>
 * </ol>
 * Le travail mis en file la nuit ne démarre que la nuit ; s'il n'est pas fini à la fin de la fenêtre, il est arrêté et
 * reprendra la nuit suivante. Vérifié toutes les 10 minutes.
 */
@ApplicationScoped
public class WebNightService extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(WebNightService.class);
    static final String DONE = "web.night.done";
    static final int MAX_PREVENTIVE = 200;

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MediaConfig config;
    @Inject
    WebSettings webSettings;
    @Inject
    WebPrepService prep;
    @Inject
    WebCache cache;
    @Inject
    UpNextService upNext;
    @Inject
    AppSettings settings;

    public WebNightService() {
        super("web-night");
    }

    void onStart(@Observes StartupEvent e) {
        if (config.webWorkerEnabled()) {
            start();
        }
    }

    void onStop(@Observes ShutdownEvent e) {
        stop();
    }

    public record NightReport(int orphans, int evicted, int queued) {
    }

    @Override
    protected Outcome step() throws Exception {
        if (webSettings.nightOpen() && prep.usable()) {
            String tonight = LocalDate.now().toString();
            if (!tonight.equals(settings.get(DONE).orElse(null))) {
                settings.put(DONE, tonight);
                NightReport r = runNight();
                LOG.infof("Lecteur web, nuit : %d préparation(s) orpheline(s) et %d ancienne(s) effacée(s), %d mise(s) en file",
                        r.orphans(), r.evicted(), r.queued());
            }
        }
        return new Idle(Duration.ofMinutes(10));
    }

    /** Une nuit de travail (aussi appelée par les tests). */
    public NightReport runNight() throws SQLException {
        int orphans = removeOrphans();
        int evicted = trim();
        int queued = webSettings.preventive() ? preventive() : 0;
        return new NightReport(orphans, evicted, queued);
    }

    /** Préparations dont la source a disparu ou changé (taille, date) : effacées (hors travail en cours). */
    int removeOrphans() throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT j.cache_key FROM web_job j LEFT JOIN media_file f ON f.id = j.media_file_id
                     WHERE j.status <> 'RUNNING' AND (f.id IS NULL OR NOT f.available OR f.file_size <> j.source_size
                         OR f.last_modified IS DISTINCT FROM j.source_modified)""");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        for (String k : keys) {
            cache.delete(k);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement st = c.prepareStatement("DELETE FROM web_job WHERE cache_key = ? AND status <> 'RUNNING'")) {
                st.setString(1, k);
                st.executeUpdate();
            }
        }
        return keys.size();
    }

    /** Les moins récemment lues jusqu'à 85 % du plafond. */
    int trim() throws SQLException {
        long max = prep.maxBytes();
        long used = prep.usedBytes();
        long target = (long) (max * 0.85);
        if (used <= target) {
            return 0;
        }
        long before = count();
        prep.evict(used, 0, target, 0, null);
        return (int) Math.max(0, before - count());
    }

    private long count() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT count(*) FROM web_job WHERE status = 'READY'");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Épisodes à préparer cette nuit : suivants des animés en cours, puis ajouts récents. */
    int preventive() throws SQLException {
        Set<Long> episodes = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT DISTINCT ON (s.anime_id, p.user_id) p.episode_id
                     FROM playback_progress p JOIN episode e ON e.id = p.episode_id JOIN season s ON s.id = e.season_id
                     WHERE p.updated_at > now() - interval '30 days'
                     ORDER BY s.anime_id, p.user_id, p.updated_at DESC""");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                long ep = rs.getLong(1);
                Optional<UpNextService.Target> n1 = upNext.following(ep);
                if (n1.isPresent()) {
                    episodes.add(n1.get().episodeId());
                    upNext.following(n1.get().episodeId()).ifPresent(n2 -> episodes.add(n2.episodeId()));
                }
            }
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id
                     WHERE f.available AND f.first_seen_at > now() - interval '7 days'
                     ORDER BY f.first_seen_at DESC, e.id LIMIT ?""")) {
            st.setInt(1, MAX_PREVENTIVE);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    episodes.add(rs.getLong(1));
                }
            }
        }
        int queued = 0;
        for (long ep : episodes) {
            if (queued >= MAX_PREVENTIVE) {
                break;
            }
            if (prepareEpisode(ep, WebPrepService.PRIORITY_NIGHT)) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * Met un épisode en file (préventif ou admin) : préparation de base, puis conversion si elle sert (la conversion
     * suit d'elle-même la préparation de base ; déjà prête : demandée tout de suite). Vrai si quelque chose est en file.
     */
    public boolean prepareEpisode(long episodeId, int priority) throws SQLException {
        long fileId;
        long size;
        OffsetDateTime modified;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT f.id, f.file_size, f.last_modified FROM episode e JOIN media_file f ON f.id = e.media_file_id
                     WHERE e.id = ? AND f.available""")) {
            st.setLong(1, episodeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                fileId = rs.getLong(1);
                size = rs.getLong(2);
                modified = rs.getObject(3, OffsetDateTime.class);
            }
        }
        Optional<WebPrepService.Job> base = prep.job(fileId, WebPrepService.BASE);
        String key = WebCache.key(WebPrepService.BASE, fileId, size, modified);
        if (base.isEmpty() || !base.get().key().equals(key)) {
            prep.enqueue(WebPrepService.BASE, fileId, size, modified, null, priority);
            return true;
        }
        WebPrepService.Job b = base.get();
        if (!"READY".equals(b.status()) || b.manifest() == null) {
            if ("QUEUED".equals(b.status()) && b.priority() > priority) {
                prep.enqueue(WebPrepService.BASE, fileId, size, modified, null, priority);
            }
            return false;
        }
        WebManifest m = b.manifest();
        if (!m.convertible()) {
            return false;
        }
        WebDecision.Result d = WebDecision.decide(m, WebDecision.DEFAULT);
        if (d.mode() != WebDecision.Mode.UNSUPPORTED || !d.convert()) {
            return false;
        }
        if (priority >= WebPrepService.PRIORITY_NIGHT && !WebManifest.universalVideo(m.video()) && !webSettings.preventiveVideo()) {
            return false;
        }
        Optional<WebPrepService.Job> conv = prep.job(fileId, WebPrepService.CONV);
        if (conv.isPresent() && conv.get().key().equals(WebPrepService.convKey(fileId, size, modified, webSettings.maxHeight()))
                && ("READY".equals(conv.get().status()) || "FAILED".equals(conv.get().status()) || conv.get().priority() <= priority)) {
            return false;
        }
        prep.enqueue(WebPrepService.CONV, fileId, size, modified, m, priority);
        return true;
    }
}
