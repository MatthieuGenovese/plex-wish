-- Phase 2 : un reset de mot de passe par l'admin révoque les sessions de l'utilisateur.
-- (V1 est déjà commitée : on ne la modifie pas.)
ALTER TABLE refresh_token DROP CONSTRAINT refresh_token_revoked_reason_check;
ALTER TABLE refresh_token ADD CONSTRAINT refresh_token_revoked_reason_check
    CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'USER_DISABLED', 'REUSE_DETECTED', 'PASSWORD_RESET'));
