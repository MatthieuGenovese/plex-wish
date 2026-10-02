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

    /** Champs suivants : remplis par la tâche des métadonnées (ARCHITECTURE §15), vides tant que non apparié. */
    @Column(name = "alternative_title")
    public String alternativeTitle;

    public String synopsis;

    @Column(name = "poster_url")
    public String posterUrl;

    public Integer year;

    @Column(name = "metadata_provider_id")
    public String metadataProviderId;

    /** Fournisseur de la fiche (ex. ANILIST). */
    @Column(name = "metadata_provider")
    public String metadataProvider;

    /** Langue du synopsis (ISO 639-1, ex. "en"). */
    @Column(name = "synopsis_language")
    public String synopsisLanguage;

    @Column(name = "poster_large_url")
    public String posterLargeUrl;

    /** Page de la fiche chez le fournisseur (attribution). */
    @Column(name = "metadata_url")
    public String metadataUrl;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
