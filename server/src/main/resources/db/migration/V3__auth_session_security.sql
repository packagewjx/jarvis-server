ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS email TEXT,
    ADD COLUMN IF NOT EXISTS phone TEXT,
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS auth_sessions (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_users(user_id) ON DELETE CASCADE,
    refresh_token_hash TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT,
    user_agent TEXT,
    ip TEXT
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_updated
    ON auth_sessions (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires
    ON auth_sessions (expires_at);

CREATE TABLE IF NOT EXISTS auth_revoked_tokens (
    jti TEXT PRIMARY KEY,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_revoked_tokens_expires
    ON auth_revoked_tokens (expires_at);

CREATE TABLE IF NOT EXISTS auth_password_reset_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_users(user_id) ON DELETE CASCADE,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    used_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_auth_password_reset_user
    ON auth_password_reset_tokens (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_password_reset_expires
    ON auth_password_reset_tokens (expires_at);

CREATE TABLE IF NOT EXISTS auth_verification_challenges (
    challenge_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_users(user_id) ON DELETE CASCADE,
    channel TEXT NOT NULL,
    target TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    used_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_auth_verification_user_created
    ON auth_verification_challenges (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_verification_expires
    ON auth_verification_challenges (expires_at);
