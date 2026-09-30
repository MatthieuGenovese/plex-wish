package fr.plexwish.animeserver.library;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Fichier vidéo vu par le scan. Le chemin n'est jamais renvoyé aux utilisateurs (§4.2). */
@Entity
@Table(name = "media_file")
public class MediaFile extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "relative_path", nullable = false)
    public String relativePath;

    @Column(name = "file_name", nullable = false)
    public String fileName;

    @Column(name = "file_size", nullable = false)
    public long fileSize;

    @Column(name = "last_modified")
    public Instant lastModified;

    @Column(nullable = false, length = 10)
    public String container;

    /** EPISODE, EXTRA, UNRESOLVED ou IGNORED. */
    @Column(nullable = false, length = 12)
    public String kind;

    @Column(nullable = false)
    public boolean available;

    @Column(name = "missing_since")
    public Instant missingSince;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;
}
