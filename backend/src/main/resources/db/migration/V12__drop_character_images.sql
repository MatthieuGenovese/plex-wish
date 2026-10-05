-- Décision du 2026-10-05 : plus d'image de personnage, seules les photos des comédiens sont gardées.
-- Les fichiers déjà téléchargés ne sont plus référencés : le ménage du démarrage (CastWorker) les supprime.
ALTER TABLE cast_character DROP COLUMN image_url;
DELETE FROM cast_image i WHERE NOT EXISTS (SELECT 1 FROM person p WHERE p.image_url = i.source_url);
