-- Phase 6 : métadonnées des animés (ARCHITECTURE §15).

-- Fiche affichée : fournisseur et langue du synopsis enregistrés avec elle (un autre fournisseur pourra
-- s'ajouter, ex. TMDB pour des synopsis en français).
ALTER TABLE anime
    ADD COLUMN metadata_provider VARCHAR(20),
    ADD COLUMN synopsis_language VARCHAR(10),
    ADD COLUMN poster_large_url  TEXT,
    ADD COLUMN metadata_url      TEXT;

-- État de l'appariement, un par animé. Pas de ligne = jamais traité (à faire).
CREATE TABLE anime_metadata_match (
    anime_id        BIGINT PRIMARY KEY REFERENCES anime (id) ON DELETE CASCADE,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'MATCHED', 'DOUBTFUL', 'UNMATCHED', 'MANUAL')),
    provider        VARCHAR(20)  NOT NULL,
    provider_id     TEXT,
    -- Similarité de titre du candidat retenu (ou du meilleur candidat écarté), entre 0 et 1.
    score           NUMERIC(4, 3),
    reason          VARCHAR(20),
    -- Meilleurs candidats, pour que l'admin choisisse sans refaire de recherche.
    candidates      JSONB,
    -- Correction manuelle : jamais écrasée par la récupération automatique ni par un rescan.
    locked          BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      TEXT         NOT NULL
);

CREATE INDEX anime_metadata_match_status_idx ON anime_metadata_match (status);
