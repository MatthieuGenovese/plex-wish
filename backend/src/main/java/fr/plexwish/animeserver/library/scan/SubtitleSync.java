package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.library.subs.ExternalSubtitleRepository;
import fr.plexwish.animeserver.library.parse.SubtitleNames;
import fr.plexwish.animeserver.library.subs.ExternalSubtitleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Partie « sous-titres externes » du scan (1.3.0, ARCHITECTURE §7.8), après les vidéos : chaque {@code .ass} / {@code .ssa}
 * vu est associé à un épisode (correction de l'admin, sinon {@link ExternalSubtitleService#link}), sa fin lue s'il a
 * changé ; ceux qui ont disparu sont marqués indisponibles. Rapport : sous-titres sans épisode, et sous-titres qui
 * dépassent nettement la durée de leur épisode (mauvais épisode ou mauvaise numérotation, le plus souvent).
 */
@ApplicationScoped
class SubtitleSync {

    /** Marge du contrôle de durée : une minute, ou 5 % de l'épisode si c'est plus. */
    static final double DURATION_MARGIN_SECONDS = 60;
    static final double DURATION_MARGIN_RATIO = 0.05;

    @Inject
    ExternalSubtitleService subtitles;
    @Inject
    ExternalSubtitleRepository repository;

    private record Known(long size, OffsetDateTime modified, Long cueSize, OffsetDateTime cueModified, BigDecimal lastCue) {
    }

    private record Manual(Long episodeId) {
    }

    List<LibraryScanner.Issue> sync(Connection c, Path root, List<LibraryScanner.Found> found, OffsetDateTime scanTime,
                                    ScanStats stats) throws SQLException {
        List<LibraryScanner.Issue> issues = new ArrayList<>();
        Map<String, Known> known = new HashMap<>();
        Map<String, Manual> overrides = new HashMap<>();
        Map<String, Long> sameName = new HashMap<>();
        Map<String, Long> episodes = new HashMap<>();
        Map<String, Integer> lastEpisodes = new HashMap<>();
        for (ExternalSubtitleRepository.KnownSubtitle k : repository.known(c)) {
            known.put(k.relativePath(), new Known(k.size(), k.modified(), k.cueSize(), k.cueModified(), k.lastCue()));
        }
        for (ExternalSubtitleRepository.SubtitleOverride o : repository.overrides(c)) {
            overrides.put(o.relativePath(), new Manual(o.episodeId()));
        }
        if (!found.isEmpty()) {
            for (ExternalSubtitleRepository.VideoEpisode v : repository.linkedVideos(c)) {
                String path = v.relativePath();
                int slash = path.lastIndexOf('/');
                String dir = slash < 0 ? "" : path.substring(0, slash);
                sameName.put(dir + '\0' + SubtitleNames.videoStem(path.substring(slash + 1)), v.episodeId());
            }
            for (ExternalSubtitleRepository.EpisodeKey e : repository.episodeKeys(c)) {
                episodes.put(e.normalizedTitle() + '|' + e.seasonNumber() + '|' + e.episodeNumber(), e.episodeId());
                lastEpisodes.merge(e.normalizedTitle() + '|' + e.seasonNumber(), e.episodeNumber(), Math::max);
            }
        }
        ExternalSubtitleService.Lookup lookup = new ExternalSubtitleService.Lookup() {
            @Override
            public Long sameName(String dir, String stem) {
                return sameName.get(dir + '\0' + stem);
            }

            @Override
            public Long episode(String normalizedTitle, int season, int episode) {
                return episodes.get(normalizedTitle + '|' + season + '|' + episode);
            }

            @Override
            public Integer lastEpisode(String normalizedTitle, int season) {
                return lastEpisodes.get(normalizedTitle + '|' + season);
            }
        };

        List<ExternalSubtitleRepository.ScannedSubtitle> rows = new ArrayList<>();
        for (LibraryScanner.Found f : found) {
            stats.externalSubtitles++;
            Manual o = overrides.get(f.path());
            ExternalSubtitleService.Link link;
            if (o != null) {
                link = new ExternalSubtitleService.Link(o.episodeId(), o.episodeId() == null ? null : "MANUAL", null, null);
            } else {
                link = subtitles.link(f.path(), lookup);
            }
            if (link.episodeId() != null) {
                stats.subtitlesLinked++;
            } else if (o == null) {
                stats.subtitlesUnmatched++;
                String detail = link.season() != null
                        ? "saison " + link.season() + ", épisode " + link.episode() + " : épisode absent de la bibliothèque"
                        : "aucune vidéo du même nom dans le dossier, aucun numéro d'épisode reconnu";
                issues.add(new LibraryScanner.Issue(null, f.path(), "SUBTITLE_UNMATCHED", LibraryScanner.topFolder(f.path()),
                        detail, link.season(), link.episode(), null, null, null));
            }
            Known k = known.get(f.path());
            BigDecimal lastCue;
            if (k != null && Objects.equals(k.cueSize(), f.size()) && Objects.equals(k.cueModified(), f.modified())) {
                lastCue = k.lastCue();
            } else {
                OptionalDouble d = ExternalSubtitleService.lastCue(root.resolve(f.path()));
                lastCue = d.isPresent() ? BigDecimal.valueOf(d.getAsDouble()).setScale(3, RoundingMode.HALF_UP) : null;
            }
            String name = f.fileName();
            rows.add(new ExternalSubtitleRepository.ScannedSubtitle(f.path(), name, f.size(), f.modified(), link.episodeId(),
                    link.match(), SubtitleNames.forced(name), SubtitleNames.language(name).orElse(null), lastCue));
            if (rows.size() == 500) {
                repository.upsertScanned(c, rows, scanTime);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) {
            repository.upsertScanned(c, rows, scanTime);
        }
        repository.markUnavailable(c, scanTime);
        issues.addAll(durationIssues(c, stats));
        return issues;
    }

    /** Sous-titres dont le dernier texte arrive nettement après la fin de la vidéo (durée connue par l'analyse ffprobe). */
    private List<LibraryScanner.Issue> durationIssues(Connection c, ScanStats stats) throws SQLException {
        List<LibraryScanner.Issue> issues = new ArrayList<>();
        for (ExternalSubtitleRepository.DurationMismatch m
                : repository.durationMismatches(c, DURATION_MARGIN_SECONDS, DURATION_MARGIN_RATIO)) {
            stats.subtitleDurationIssues++;
            issues.add(new LibraryScanner.Issue(null, m.relativePath(), "SUBTITLE_DURATION", m.animeTitle(),
                    "dernier sous-titre à " + clock(m.lastCueSeconds()) + ", vidéo de " + clock(m.videoSeconds())
                            + " : mauvais épisode ?", m.seasonNumber(), m.episodeNumber(), null, null, null));
        }
        return issues;
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}
