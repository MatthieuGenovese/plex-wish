-- Phase 1 : tables d'authentification (utilisées à partir de la phase 2).
-- Ne jamais modifier une migration déjà commitée : en créer une nouvelle.

-- "user" est un mot réservé PostgreSQL, d'où app_user.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Unicité insensible à la casse : "Admin" et "admin" sont le même compte.
CREATE UNIQUE INDEX app_user_username_uk ON app_user (lower(username));
CREATE UNIQUE INDEX app_user_email_uk ON app_user (lower(email)) WHERE email IS NOT NULL;

-- Le refresh token en clair n'est jamais stocké : seulement son SHA-256 (hex).
CREATE TABLE refresh_token (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash     CHAR(64)    NOT NULL UNIQUE,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(20) CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'USER_DISABLED', 'REUSE_DETECTED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
