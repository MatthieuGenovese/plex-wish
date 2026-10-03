-- Étape 6.3 : distribution (personnages et comédiens de doublage), ARCHITECTURE §18-19.
-- Source unique : AniList. Une personne est identifiée par (fournisseur, identifiant chez le fournisseur).

CREATE TABLE person (
    id           BIGSERIAL PRIMARY KEY,
    provider     VARCHAR(10) NOT NULL,
    provider_id  TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    native_name  TEXT,
    image_url    TEXT,
    fetched_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (provider, provider_id)
);

CREATE TABLE cast_character (
    id           BIGSERIAL PRIMARY KEY,
    provider     VARCHAR(10) NOT NULL,
    provider_id  TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    native_name  TEXT,
    image_url    TEXT,
    fetched_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (provider, provider_id)
);

-- Distribution d'un animé : un personnage une seule fois par langue, avec son meilleur rôle.
-- Un même comédien peut jouer plusieurs personnages.
CREATE TABLE anime_cast (
    anime_id      BIGINT      NOT NULL REFERENCES anime (id) ON DELETE CASCADE,
    character_id  BIGINT      NOT NULL REFERENCES cast_character (id) ON DELETE CASCADE,
    language      VARCHAR(10) NOT NULL,
    person_id     BIGINT REFERENCES person (id) ON DELETE CASCADE,
    role          VARCHAR(10) NOT NULL CHECK (role IN ('MAIN', 'SUPPORTING')),
    position      INTEGER     NOT NULL,
    -- Fiche AniList (saison) d'où vient le rôle.
    source_id     TEXT        NOT NULL,
    PRIMARY KEY (anime_id, character_id, language)
);
CREATE INDEX anime_cast_person_idx ON anime_cast (person_id);

-- État de la récupération par animé (idempotent, reprenable). source_id : fiche AniList appariée au moment de la
-- récupération (si l'appariement change, la distribution est refaite).
CREATE TABLE anime_cast_state (
    anime_id         BIGINT PRIMARY KEY REFERENCES anime (id) ON DELETE CASCADE,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'OK', 'NONE', 'EXCLUDED', 'FAILED')),
    provider         VARCHAR(10) NOT NULL,
    source_id        TEXT,
    seasons          INTEGER     NOT NULL DEFAULT 0,
    roles            INTEGER     NOT NULL DEFAULT 0,
    fetched_at       TIMESTAMPTZ,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Images (personnages, comédiens) stockées sur le NAS, une ligne par URL source : même mécanisme que les affiches.
CREATE TABLE cast_image (
    source_url       TEXT PRIMARY KEY,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'OK', 'FAILED')),
    public_id        CHAR(32) UNIQUE,
    relative_path    TEXT,
    sha256           CHAR(64),
    content_type     VARCHAR(20),
    bytes            BIGINT,
    fetched_at       TIMESTAMPTZ,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       TEXT,
    CHECK (status <> 'OK' OR (public_id IS NOT NULL AND relative_path IS NOT NULL AND sha256 IS NOT NULL))
);
CREATE INDEX cast_image_status_idx ON cast_image (status);
