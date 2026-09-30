package fr.plexwish.animeserver.library;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Épisode ; visible seulement si son fichier est disponible. */
@Entity
@Table(name = "episode")
public class Episode extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id")
    public Season season;

    @Column(name = "episode_number", nullable = false)
    public int episodeNumber;

    public String title;

    public String synopsis;

    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_file_id")
    public MediaFile mediaFile;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
