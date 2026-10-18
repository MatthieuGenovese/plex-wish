-- Remux à la volée (docs/LIVE-REMUX.md §4) : images clés de la piste vidéo, relevées à la première lecture.
-- probed_size / probed_modified : état du fichier au moment du relevé (fichier modifié → nouveau relevé).
-- times_ms : instants des images clés (ms, croissants) ; duration_ms : durée du fichier.
CREATE TABLE media_keyframes (
    media_file_id   BIGINT PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    probed_size     BIGINT      NOT NULL,
    probed_modified TIMESTAMPTZ,
    times_ms        INTEGER[]   NOT NULL,
    duration_ms     INTEGER     NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
