-- D1.4 : invitations. L'admin crée un compte SANS mot de passe et obtient un lien à usage unique, valable 72 h, où la
-- personne choisit le sien (fini les mots de passe temporaires transmis). Le même mécanisme sert à la
-- réinitialisation d'un mot de passe oublié. Le jeton en clair n'est jamais stocké : seulement son SHA-256.

-- Compte invité : pas encore de mot de passe (aucune connexion possible tant que le lien n'a pas servi).
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE invitation (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL UNIQUE,
    purpose    VARCHAR(10) NOT NULL CHECK (purpose IN ('INVITE', 'RESET')),
    created_by VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX invitation_user_idx ON invitation (user_id);
