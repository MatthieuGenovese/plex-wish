-- Phase 10 : préparation des épisodes pour le navigateur (docs/WEB-PLAYER.md). Une ligne par fichier et par sorte de
-- préparation : BASE = analyse des pistes, sous-titres et polices extraits, copie HLS fMP4 sans ré-encodage si besoin.
-- (10.3 ajoutera les conversions : autres valeurs de « kind ».) Dossier : cache web (WEB_CACHE_PATH), nom = empreinte.
CREATE TABLE web_job (
    media_file_id   BIGINT       NOT NULL REFERENCES media_file (id) ON DELETE CASCADE,
    kind            VARCHAR(12)  NOT NULL CHECK (kind IN ('BASE')),
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),
    -- Étape en cours (RUNNING) : PROBE, SUBS, HLS, VERIFY.
    phase           VARCHAR(10),
    priority        INTEGER      NOT NULL DEFAULT 0,
    cache_key       VARCHAR(64)  NOT NULL,
    source_size     BIGINT       NOT NULL,
    source_modified TIMESTAMPTZ,
    -- Pistes, sous-titres, polices et copie (WebManifest), écrit dès l'analyse.
    manifest        JSONB,
    bytes           BIGINT,
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    last_read_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    error           TEXT,
    next_attempt_at TIMESTAMPTZ,
    blocked         VARCHAR(12),
    PRIMARY KEY (media_file_id, kind)
);
CREATE UNIQUE INDEX web_job_key_idx ON web_job (cache_key);
CREATE INDEX web_job_queue_idx ON web_job (status, priority, requested_at);
