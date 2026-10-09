-- D1.3 : réglages de l'installation (fin de l'assistant de premier lancement, seuils d'espace disque).
-- Valeurs NON secrètes uniquement : les secrets (clé TMDB, jeton du DNS dynamique) sont des fichiers 600 dans le
-- volume des secrets, jamais en base (une sauvegarde de la base n'en contient donc aucun).
CREATE TABLE app_setting (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
