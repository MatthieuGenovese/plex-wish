package fr.plexwish.animeserver.live;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vrais ffmpeg / ffprobe pour les tests de la lecture à la volée (tests ignorés s'ils manquent). */
final class LiveTools {

    private LiveTools() {
    }

    static boolean has(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasEncoder(String name) {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            return out.contains(" " + name + " ");
        } catch (Exception e) {
            return false;
        }
    }

    /** Lance l'outil, échoue si le code de sortie n'est pas 0 ; renvoie la sortie standard. */
    static String run(String tool, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(tool));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, String.join(" ", cmd));
        return out;
    }
}
