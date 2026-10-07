-- Phase Polish, S3 (ARCHITECTURE §24.6) : genres AniList de chaque animé apparié.
-- Valeurs d'AniList telles quelles (anglais : « Action », « Slice of Life »…) ; libellés français côté API.
CREATE TABLE anime_genre (
    anime_id BIGINT      NOT NULL REFERENCES anime (id) ON DELETE CASCADE,
    genre    VARCHAR(40) NOT NULL,
    PRIMARY KEY (anime_id, genre)
);
CREATE INDEX anime_genre_genre_idx ON anime_genre (genre);

-- Date de récupération des genres : NULL = à récupérer (rattrapage par lots de 50 pour les fiches déjà appariées).
ALTER TABLE anime ADD COLUMN genres_fetched_at TIMESTAMPTZ;
