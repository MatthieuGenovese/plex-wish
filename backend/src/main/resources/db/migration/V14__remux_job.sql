-- Phase 9.2 : remux à la demande (ARCHITECTURE §23). Une ligne par fichier source ; la copie est valable tant que
-- la source n'a pas changé (cache_key = empreinte de l'identifiant, de la taille et de la date de la source).
CREATE TABLE remux_job (
    media_file_id    BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),
    -- 0 : demandé par un utilisateur (passe en premier) ; 1 : préparé à l'avance par l'admin.
    priority         SMALLINT    NOT NULL DEFAULT 0,
    cache_key        CHAR(64)    NOT NULL,
    source_size      BIGINT      NOT NULL,
    source_modified  TIMESTAMPTZ,
    variant          VARCHAR(20),
    bytes            BIGINT,
    duration_seconds NUMERIC(10, 3),
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    last_read_at     TIMESTAMPTZ,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    -- Attente faute de place (CACHE_FULL) : la demande reste en file, retentée régulièrement.
    blocked          VARCHAR(20),
    error            TEXT
);
CREATE INDEX remux_job_queue_idx ON remux_job (status, priority, requested_at);
