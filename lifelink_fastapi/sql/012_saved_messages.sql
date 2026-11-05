-- Participant-scoped private bookmarks for conversation messages.
-- A saved message is visible only to the participant who saved it.

CREATE TABLE IF NOT EXISTS saved_messages (
    id VARCHAR(128) PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_messages_message_user UNIQUE (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS ix_saved_messages_user_saved_at
    ON saved_messages (user_id, saved_at DESC);
CREATE INDEX IF NOT EXISTS ix_saved_messages_conversation_user
    ON saved_messages (conversation_id, user_id);
