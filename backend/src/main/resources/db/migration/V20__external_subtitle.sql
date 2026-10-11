-- 1.3.0 : sous-titres externes (.ass / .ssa posés à côté des vidéos ou ailleurs dans le dossier de l'animé).
-- Associés à un épisode au scan : même dossier et même nom que la vidéo, sinon numéro reconnu par le parser des vidéos
-- (saison du nom, sinon du dossier, sinon 1). Le fichier n'est jamais modifié : la base garde seulement ce qu'on en sait.
CREATE TABLE external_subtitle (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    relative_path    TEXT        NOT NULL UNIQUE,
    file_name        TEXT        NOT NULL,
    file_size        BIGINT      NOT NULL,
    last_modified    TIMESTAMPTZ,
    available        BOOLEAN     NOT NULL DEFAULT TRUE,
    last_seen_at     TIMESTAMPTZ NOT NULL,
    episode_id       BIGINT REFERENCES episode (id) ON DELETE SET NULL,
    -- SAME_NAME : même dossier et même nom que la vidéo ; NAME : numéro reconnu dans le nom ; MANUAL : correction admin.
    match            VARCHAR(10) CHECK (match IN ('SAME_NAME', 'NAME', 'MANUAL')),
    forced           BOOLEAN     NOT NULL DEFAULT FALSE,
    language         VARCHAR(8),
    -- Fin du dernier sous-titre (s), lue dans le fichier quand il change (cue_size / cue_modified : état lu).
    last_cue_seconds NUMERIC(10, 3),
    cue_size         BIGINT,
    cue_modified     TIMESTAMPTZ
);
CREATE INDEX external_subtitle_episode_idx ON external_subtitle (episode_id) WHERE available;

-- Correction de l'admin (rattacher à un épisode, ou détacher : episode_id NULL), jamais écrasée par un rescan.
CREATE TABLE external_subtitle_override (
    relative_path TEXT PRIMARY KEY,
    episode_id    BIGINT REFERENCES episode (id) ON DELETE CASCADE,
    created_by    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE scan_issue DROP CONSTRAINT scan_issue_category_check;
ALTER TABLE scan_issue ADD CONSTRAINT scan_issue_category_check CHECK (category IN
    ('UNRESOLVED', 'DUPLICATE', 'MULTI_EPISODE', 'DECIMAL_EPISODE', 'SEASON_MISMATCH', 'MISSING', 'UNREADABLE',
     'SUBTITLE_UNMATCHED', 'SUBTITLE_DURATION'));
