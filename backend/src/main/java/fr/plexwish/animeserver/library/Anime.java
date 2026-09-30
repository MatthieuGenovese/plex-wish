package fr.plexwish.animeserver.library;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Animé = dossier de premier niveau de /media. Créé par le scan uniquement. */
@Entity
@Table(name = "anime")
public class Anime extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String title;

    @Column(name = "normalized_title", nullable = false)
    public String normalizedTitle;

    /** Les 4 champs suivants restent vides à cette étape (métadonnées : plus tard). */
    @Column(name = "alternative_title")
    public String alternativeTitle;

    public String synopsis;

    @Column(name = "poster_url")
    public String posterUrl;

    public Integer year;

    @Column(name = "metadata_provider_id")
    public String metadataProviderId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
