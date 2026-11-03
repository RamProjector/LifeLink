-- Saved messages are private to the authenticated participant who saved them.
-- Duplicate saves are idempotent at the (user, message) level.
CREATE TABLE IF NOT EXISTS saved_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id VARCHAR(128) NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_messages_user_message UNIQUE (user_id, message_id)
);

CREATE INDEX IF NOT EXISTS ix_saved_messages_user_saved_at
    ON saved_messages (user_id, saved_at);
CREATE INDEX IF NOT EXISTS ix_saved_messages_conversation_user
    ON saved_messages (conversation_id, user_id);
