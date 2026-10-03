-- Étape 6.2 : affiches téléchargées sur le NAS (ARCHITECTURE §17).
-- Une ligne par animé : l'affiche locale (fichier nommé par son empreinte SHA-256, relatif au dossier des affiches),
-- sa source (fournisseur, URL), sa date de récupération (affiches TMDB : 6 mois au plus, conditions de l'API TMDB),
-- et l'état du téléchargement (reprenable, avec nouvel essai espacé).
CREATE TABLE anime_poster (
    anime_id        BIGINT PRIMARY KEY REFERENCES anime (id) ON DELETE CASCADE,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'OK', 'FAILED')),
    provider        VARCHAR(10) NOT NULL CHECK (provider IN ('TMDB', 'ANILIST')),
    source_url      TEXT        NOT NULL,
    -- Identifiant aléatoire de l'URL publique /api/posters/{public_id} : le client ne donne jamais de chemin.
    public_id       CHAR(32) UNIQUE,
    relative_path   TEXT,
    sha256          CHAR(64),
    content_type    VARCHAR(20),
    bytes           BIGINT,
    fetched_at      TIMESTAMPTZ,
    -- Source refusée (pas une image, trop grosse, 404…) ou en échec répété : plus retentée tant qu'elle ne change pas.
    failed_source   TEXT,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status <> 'OK' OR (public_id IS NOT NULL AND relative_path IS NOT NULL AND sha256 IS NOT NULL))
);

CREATE INDEX anime_poster_sha_idx ON anime_poster (sha256);
CREATE INDEX anime_poster_status_idx ON anime_poster (status);
