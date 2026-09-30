-- Détail des doublons et des désaccords dans le rapport de scan : de quoi vérifier, sans ouvrir les
-- fichiers, que deux fichiers portent vraiment le même épisode (et d'où vient leur numéro de saison).
ALTER TABLE scan_issue
    ADD COLUMN season_number      INTEGER,
    ADD COLUMN episode_number     INTEGER,
    -- Doublon : chemin relatif du fichier conservé (relative_path = fichier écarté).
    ADD COLUMN kept_relative_path TEXT,
    ADD COLUMN season_source      VARCHAR(20),
    ADD COLUMN kept_season_source VARCHAR(20);
