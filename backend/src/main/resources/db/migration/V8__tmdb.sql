-- Étape 6.1 : synopsis (et titre) en français depuis TMDB (ARCHITECTURE §16).

-- Date de récupération des champs AniList déjà stockés dans anime (fournisseur : metadata_provider,
-- langue du synopsis : synopsis_language).
ALTER TABLE anime ADD COLUMN metadata_fetched_at TIMESTAMPTZ;
UPDATE anime SET metadata_fetched_at = now() WHERE metadata_provider IS NOT NULL;

-- Appariement TMDB et champs récupérés (fournisseur TMDB, langue, date de récupération).
-- Conditions de l'API TMDB : rien n'est conservé plus de 6 mois (rafraîchi à 5 mois, effacé à 6).
CREATE TABLE anime_tmdb (
    anime_id         BIGINT PRIMARY KEY REFERENCES anime (id) ON DELETE CASCADE,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'MATCHED', 'DOUBTFUL', 'UNMATCHED', 'MANUAL')),
    tmdb_type        VARCHAR(5) CHECK (tmdb_type IN ('tv', 'movie')),
    tmdb_id          BIGINT,
    score            NUMERIC(4, 3),
    reason           VARCHAR(20),
    candidates       JSONB,
    locked           BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       TEXT,
    -- Champs TMDB : langue et date de récupération (null = rien de conservé).
    language         VARCHAR(10),
    title            TEXT,
    synopsis         TEXT,
    poster_path      TEXT,
    fetched_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT        NOT NULL,
    CHECK ((tmdb_type IS NULL) = (tmdb_id IS NULL))
);

CREATE INDEX anime_tmdb_status_idx ON anime_tmdb (status);
CREATE INDEX anime_tmdb_fetched_idx ON anime_tmdb (fetched_at);
