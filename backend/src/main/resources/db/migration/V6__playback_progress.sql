-- Phase 5 : progression de lecture, par utilisateur et par épisode.
CREATE TABLE playback_progress (
    user_id          BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    episode_id       BIGINT      NOT NULL REFERENCES episode (id) ON DELETE CASCADE,
    position_seconds INTEGER     NOT NULL CHECK (position_seconds >= 0),
    duration_seconds INTEGER     NOT NULL CHECK (duration_seconds > 0),
    -- Terminé au-delà de 90 % (calculé à l'écriture : la liste « continuer » reste une simple requête).
    completed        BOOLEAN     NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, episode_id),
    CHECK (position_seconds <= duration_seconds)
);

-- « Continuer à regarder » : dernières lectures de l'utilisateur.
CREATE INDEX playback_progress_recent_idx ON playback_progress (user_id, updated_at DESC);
