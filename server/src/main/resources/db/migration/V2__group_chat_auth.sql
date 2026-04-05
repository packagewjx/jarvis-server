CREATE TABLE IF NOT EXISTS app_users (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_groups (
    group_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS group_invites (
    invite_code TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES chat_groups(group_id) ON DELETE CASCADE,
    expires_at BIGINT,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS group_memberships (
    user_id TEXT NOT NULL REFERENCES app_users(user_id) ON DELETE CASCADE,
    group_id TEXT NOT NULL REFERENCES chat_groups(group_id) ON DELETE CASCADE,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, group_id)
);

CREATE TABLE IF NOT EXISTS group_conversations (
    id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS group_messages (
    id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    role TEXT NOT NULL,
    sender_user_id TEXT NOT NULL DEFAULT '',
    client_message_id TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (group_id, message_id)
);

CREATE TABLE IF NOT EXISTS group_message_cards (
    id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    card_id TEXT NOT NULL,
    card_type TEXT NOT NULL,
    text_content TEXT NOT NULL DEFAULT '',
    image_url TEXT NOT NULL DEFAULT '',
    audio_url TEXT NOT NULL DEFAULT '',
    audio_mime TEXT NOT NULL DEFAULT '',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    extra_json TEXT,
    updated_at BIGINT NOT NULL,
    UNIQUE (group_id, message_id, card_id)
);

CREATE TABLE IF NOT EXISTS group_message_events (
    event_id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL,
    sender_user_id TEXT NOT NULL,
    client_message_id TEXT NOT NULL DEFAULT '',
    trace_id TEXT NOT NULL,
    event TEXT NOT NULL,
    message_id TEXT NOT NULL DEFAULT '',
    card_id TEXT NOT NULL DEFAULT '',
    seq BIGINT NOT NULL DEFAULT 0,
    timestamp_ms BIGINT NOT NULL,
    payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_invites_group_id
    ON group_invites (group_id);

CREATE INDEX IF NOT EXISTS idx_group_memberships_user
    ON group_memberships (user_id, joined_at DESC);

CREATE INDEX IF NOT EXISTS idx_group_memberships_group
    ON group_memberships (group_id, joined_at DESC);

CREATE INDEX IF NOT EXISTS idx_group_messages_group_created
    ON group_messages (group_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_group_messages_sender_client
    ON group_messages (sender_user_id, group_id, client_message_id, role);

CREATE INDEX IF NOT EXISTS idx_group_message_events_group_event
    ON group_message_events (group_id, event_id ASC);

CREATE INDEX IF NOT EXISTS idx_group_message_events_sender_group_client
    ON group_message_events (sender_user_id, group_id, client_message_id, event_id ASC);

CREATE INDEX IF NOT EXISTS idx_group_message_events_created_at
    ON group_message_events (created_at);

INSERT INTO chat_groups (group_id, name, created_at, updated_at)
VALUES ('g_default', 'Default Group', 0, 0)
ON CONFLICT (group_id) DO NOTHING;

INSERT INTO group_invites (invite_code, group_id, expires_at, disabled, created_at)
VALUES ('DEFAULT-GROUP', 'g_default', NULL, FALSE, 0)
ON CONFLICT (invite_code) DO NOTHING;
