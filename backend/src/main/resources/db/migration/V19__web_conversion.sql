-- Phase 10.3 : conversions pour le navigateur (docs/WEB-PLAYER.md §4). CONV = copie HLS lisible par tous les
-- navigateurs : vidéo H.264 8 bits (copiée si elle l'est déjà, sinon convertie) et son AAC (copié si AAC/MP3).
-- Priorité : 0 = quelqu'un attend devant l'écran, 1 = demandé par l'admin, 2 = préventif (la nuit).
ALTER TABLE web_job DROP CONSTRAINT web_job_kind_check;
ALTER TABLE web_job ADD CONSTRAINT web_job_kind_check CHECK (kind IN ('BASE', 'CONV'));
-- Durée de calcul de la dernière exécution réussie (vitesse observée sur ce NAS, affichée à l'admin).
ALTER TABLE web_job ADD COLUMN elapsed_ms BIGINT;
