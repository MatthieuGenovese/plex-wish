-- Recherche d'animés sans tenir compte des accents (« chunibyo » trouve « Chûnibyô »).
-- Extension « trusted » depuis PostgreSQL 13 : le propriétaire de la base peut la créer.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Cause d'échec d'un scan, lisible par le front (le texte de failure_reason reste pour l'humain).
ALTER TABLE scan_run ADD COLUMN failure_code VARCHAR(30)
    CHECK (failure_code IN ('MEDIA_ROOT_UNAVAILABLE', 'MASS_REMOVAL', 'INTERRUPTED', 'INTERNAL_ERROR'));

UPDATE scan_run SET failure_code = CASE
        WHEN failure_reason LIKE '%confirmMassRemoval%' THEN 'MASS_REMOVAL'
        WHEN failure_reason LIKE 'interrompu%' THEN 'INTERRUPTED'
        WHEN failure_reason LIKE 'erreur interne%' THEN 'INTERNAL_ERROR'
        ELSE 'MEDIA_ROOT_UNAVAILABLE' END
WHERE status = 'FAILED';
