-- Phase 9.1 : analyse ffprobe du catalogue (ARCHITECTURE §22). Une ligne par fichier analysé ;
-- probed_size / probed_modified : état du fichier au moment de l'analyse (fichier modifié → nouvelle analyse).
CREATE TABLE media_probe (
    media_file_id    BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    probed_size      BIGINT      NOT NULL,
    probed_modified  TIMESTAMPTZ,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('OK', 'FAILED')),
    probed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    error            TEXT,
    duration_seconds NUMERIC(10, 3),
    format_name      TEXT,
    container        VARCHAR(20),
    video_codec      TEXT,
    video_profile    TEXT,
    video_bit_depth  INTEGER,
    width            INTEGER,
    height           INTEGER,
    -- [{codec, profile, channels, language, default}] et [{codec, language, default, forced}]
    audio            JSONB       NOT NULL DEFAULT '[]',
    subtitles        JSONB       NOT NULL DEFAULT '[]',
    android_class    VARCHAR(10) CHECK (android_class IN ('DIRECT', 'REMUX', 'TRANSCODE')),
    android_reasons  TEXT,
    browser_playable BOOLEAN,
    browser_reasons  TEXT,
    rules_version    INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX media_probe_android_idx ON media_probe (android_class);

-- Test à blanc du remux (sortie nulle, rien n'est écrit) : une ligne par fichier et par commande.
CREATE TABLE remux_test_result (
    media_file_id BIGINT      NOT NULL REFERENCES media_file (id) ON DELETE CASCADE,
    variant       VARCHAR(20) NOT NULL CHECK (variant IN ('GENPTS', 'GENPTS_UNPACK')),
    ok            BOOLEAN     NOT NULL,
    exit_code     INTEGER,
    timed_out     BOOLEAN     NOT NULL DEFAULT FALSE,
    elapsed_ms    BIGINT      NOT NULL,
    bytes         BIGINT      NOT NULL,
    message       TEXT,
    tested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (media_file_id, variant)
);
