CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (user_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    role TEXT NOT NULL,
    client_message_id TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (user_id, conversation_id, message_id)
);

CREATE TABLE IF NOT EXISTS message_cards (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
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
    UNIQUE (user_id, conversation_id, message_id, card_id)
);

CREATE TABLE IF NOT EXISTS message_events (
    event_id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
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

CREATE INDEX IF NOT EXISTS idx_conversations_user_conversation
    ON conversations (user_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_messages_user_conversation_created
    ON messages (user_id, conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_messages_user_conversation_client_role
    ON messages (user_id, conversation_id, client_message_id, role);

CREATE INDEX IF NOT EXISTS idx_message_events_user_conversation_event
    ON message_events (user_id, conversation_id, event_id ASC);

CREATE INDEX IF NOT EXISTS idx_message_events_user_conversation_client
    ON message_events (user_id, conversation_id, client_message_id, event_id ASC);

CREATE INDEX IF NOT EXISTS idx_message_events_created_at
    ON message_events (created_at);
