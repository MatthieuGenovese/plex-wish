-- Phase 7 : refresh tokens des clients natifs (app Android), ARCHITECTURE §5.1.1.
-- client : WEB (cookie HttpOnly) ou ANDROID (refresh token dans le corps) ; device : libellé facultatif de l'appareil,
-- pour lister et révoquer un appareil plus tard.
ALTER TABLE refresh_token ADD COLUMN client VARCHAR(10) NOT NULL DEFAULT 'WEB' CHECK (client IN ('WEB', 'ANDROID'));
ALTER TABLE refresh_token ADD COLUMN device VARCHAR(100);
