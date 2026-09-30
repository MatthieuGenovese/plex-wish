-- Phase 3 : bibliothèque (ARCHITECTURE §4.1 et §7).

CREATE TABLE anime (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                TEXT        NOT NULL,
    -- Clé d'identification : titre en minuscules, sans accents ni ponctuation.
    normalized_title     TEXT        NOT NULL UNIQUE,
    alternative_title    TEXT,
    synopsis             TEXT,
    poster_url           TEXT,
    year                 INTEGER,
    metadata_provider_id TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE season (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    anime_id      BIGINT  NOT NULL REFERENCES anime (id) ON DELETE CASCADE,
    -- 0 = Spéciaux
    season_number INTEGER NOT NULL CHECK (season_number >= 0),
    UNIQUE (anime_id, season_number)
);

CREATE TABLE media_file (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Relatif à la racine média (/media) : jamais renvoyé aux clients.
    relative_path TEXT        NOT NULL UNIQUE,
    file_name     TEXT        NOT NULL,
    file_size     BIGINT      NOT NULL,
    last_modified TIMESTAMPTZ,
    container     VARCHAR(10) NOT NULL,
    kind          VARCHAR(12) NOT NULL CHECK (kind IN ('EPISODE', 'EXTRA', 'UNRESOLVED', 'IGNORED')),
    available     BOOLEAN     NOT NULL DEFAULT TRUE,
    missing_since TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX media_file_last_seen_idx ON media_file (last_seen_at) WHERE available;

CREATE TABLE episode (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id        BIGINT      NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    episode_number   INTEGER     NOT NULL CHECK (episode_number >= 0),
    title            TEXT,
    synopsis         TEXT,
    duration_seconds INTEGER,
    -- Null si le fichier a été réaffecté (correction manuelle) ; un fichier = au plus un épisode.
    media_file_id    BIGINT UNIQUE REFERENCES media_file (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (season_id, episode_number)
);

-- Correction manuelle par l'admin, appliquée à la place du parser à chaque scan (§7.7).
-- Clé : le chemin relatif, pour survivre à la disparition puis réapparition du fichier.
CREATE TABLE media_file_override (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    relative_path  TEXT        NOT NULL UNIQUE,
    action         VARCHAR(10) NOT NULL CHECK (action IN ('EPISODE', 'EXTRA', 'IGNORE')),
    anime_title    TEXT,
    season_number  INTEGER CHECK (season_number >= 0),
    episode_number INTEGER CHECK (episode_number >= 0),
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action <> 'EPISODE' OR (anime_title IS NOT NULL AND season_number IS NOT NULL AND episode_number IS NOT NULL))
);

CREATE TABLE scan_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    stats          JSONB,
    failure_reason TEXT,
    triggered_by   TEXT        NOT NULL
);

-- Un seul scan à la fois, garanti par la base : un deuxième RUNNING viole cet index.
CREATE UNIQUE INDEX scan_run_single_running ON scan_run ((TRUE)) WHERE status = 'RUNNING';

CREATE TABLE scan_issue (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scan_run_id   BIGINT      NOT NULL REFERENCES scan_run (id) ON DELETE CASCADE,
    media_file_id BIGINT REFERENCES media_file (id) ON DELETE SET NULL,
    relative_path TEXT        NOT NULL,
    category      VARCHAR(20) NOT NULL CHECK (category IN
                  ('UNRESOLVED', 'DUPLICATE', 'MULTI_EPISODE', 'DECIMAL_EPISODE', 'SEASON_MISMATCH', 'MISSING', 'UNREADABLE')),
    anime_title   TEXT,
    detail        TEXT
);

CREATE INDEX scan_issue_run_category_idx ON scan_issue (scan_run_id, category);
