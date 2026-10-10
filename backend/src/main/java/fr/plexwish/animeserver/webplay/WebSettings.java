package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.setup.AppSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Réglages des conversions pour le navigateur (Administration > Réglages, décisions D5, D8 ; docs/WEB-PLAYER.md §5) :
 * hauteur maximale des conversions (720 par défaut, 1080 possible), préparation préventive la nuit, conversions
 * vidéo préventives (désactivées par défaut), fenêtre de nuit (heure locale du serveur, variable TZ).
 */
@ApplicationScoped
public class WebSettings {

    public static final String MAX_HEIGHT = "web.max_height";
    public static final String PREVENTIVE = "web.preventive";
    public static final String PREVENTIVE_VIDEO = "web.preventive_video";

    /** Hauteurs proposées (D5). */
    public static final int[] HEIGHTS = {720, 1080};

    @Inject
    AppSettings settings;
    @Inject
    MediaConfig config;

    private volatile Clock clock = Clock.systemDefaultZone();
    private volatile Boolean nightOverride;

    public record View(int maxHeight, boolean preventive, boolean preventiveVideo, int nightStartHour, int nightEndHour) {
    }

    public View view() {
        return new View(maxHeight(), preventive(), preventiveVideo(), config.webNightStart(), config.webNightEnd());
    }

    public int maxHeight() {
        return settings.get(MAX_HEIGHT).map(v -> "1080".equals(v.trim()) ? 1080 : 720).orElse(720);
    }

    public boolean preventive() {
        return settings.get(PREVENTIVE).map(v -> !"false".equals(v)).orElse(true);
    }

    public boolean preventiveVideo() {
        return settings.get(PREVENTIVE_VIDEO).map("true"::equals).orElse(false);
    }

    public View save(int maxHeight, boolean preventive, boolean preventiveVideo) {
        settings.put(MAX_HEIGHT, maxHeight >= 1080 ? "1080" : "720");
        settings.put(PREVENTIVE, Boolean.toString(preventive));
        settings.put(PREVENTIVE_VIDEO, Boolean.toString(preventiveVideo));
        return view();
    }

    /** Fenêtre de nuit ouverte (travail préventif permis) : de {@code webNightStart} h à {@code webNightEnd} h. */
    public boolean nightOpen() {
        Boolean o = nightOverride;
        if (o != null) {
            return o;
        }
        return inWindow(ZonedDateTime.now(clock).toLocalTime(), config.webNightStart(), config.webNightEnd());
    }

    static boolean inWindow(LocalTime t, int start, int end) {
        int h = t.getHour();
        if (start == end) {
            return false;
        }
        return start < end ? h >= start && h < end : h >= start || h < end;
    }

    /** Tests seulement : fenêtre de nuit forcée (null = heure réelle). */
    public void nightForTests(Boolean open) {
        nightOverride = open;
    }
}
