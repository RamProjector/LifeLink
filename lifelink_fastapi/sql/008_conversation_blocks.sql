-- Enforceable conversation blocks.
--
-- A block is persisted state, not just an audit event. While a row exists the
-- blocked participant cannot send messages or share contact details in that
-- conversation, and no push is delivered to the blocker. Either participant of
-- a conversation may block the other.
--
-- Idempotent: safe to re-run.

CREATE TABLE IF NOT EXISTS conversation_blocks (
    id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    request_id VARCHAR(128) NOT NULL,
    blocker_id VARCHAR(128) NOT NULL,
    blocked_id VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, blocker_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS ix_conversation_blocks_conversation
    ON conversation_blocks (conversation_id);
CREATE INDEX IF NOT EXISTS ix_conversation_blocks_blocked
    ON conversation_blocks (blocked_id);
