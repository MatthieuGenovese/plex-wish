-- 1.3.5 : FourCC de la piste vidéo (MKV à piste en mode compatibilité Windows, « V_MS/VFW/FOURCC » : non lue par
-- Media3 sur Android, à remuxer). Null : pas de FourCC utile (cas normal d'un MKV).
ALTER TABLE media_probe ADD COLUMN video_codec_tag TEXT;

-- Les MKV déjà analysés n'ont pas cette information : nouvelle analyse (en-têtes seulement) au prochain passage.
-- probed_size = -1 les fait passer « en attente » ; leur classement actuel reste servi en attendant.
UPDATE media_probe SET probed_size = -1 WHERE status = 'OK' AND container = 'matroska';
