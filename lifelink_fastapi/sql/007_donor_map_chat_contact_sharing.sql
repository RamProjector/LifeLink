-- Donor map visibility, matched-requester-only exact location sharing,
-- in-app conversations, and explicit contact-detail sharing.
--
-- Privacy model enforced by the FastAPI service (this migration only stores state):
--   * The donor map exposes an APPROXIMATE area and a freshness timestamp only.
--     Exact donor coordinates are never returned to unauthenticated or unmatched users.
--   * Exact donor coordinates are disclosed to a requester only while an active,
--     unexpired donor_location_shares row exists for that (request, donor) pair.
--   * Conversations are scoped to the requester and donor of one request.
--   * Phone/email are shared explicitly, one field at a time, and every share is audited.

-- Donor-controlled map visibility. Opt-in is explicit and defaults to hidden.
ALTER TABLE donors ADD COLUMN IF NOT EXISTS map_visible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE donors ADD COLUMN IF NOT EXISTS map_visibility_updated_at TIMESTAMPTZ;
ALTER TABLE donors ADD COLUMN IF NOT EXISTS exact_location_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS ix_donors_map_visible
    ON donors (map_visible, available, profile_visible);

-- Matched-requester-only exact location sharing. One row per (request, donor).
CREATE TABLE IF NOT EXISTS donor_location_shares (
    id VARCHAR(128) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL REFERENCES emergency_requests(id) ON DELETE CASCADE,
    donor_id VARCHAR(128) NOT NULL REFERENCES donors(id) ON DELETE CASCADE,
    requester_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'revoked', 'expired')),
    shared_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, donor_id)
);

CREATE INDEX IF NOT EXISTS ix_donor_location_shares_request_donor
    ON donor_location_shares (request_id, donor_id, status);
CREATE INDEX IF NOT EXISTS ix_donor_location_shares_requester
    ON donor_location_shares (requester_id, status);

-- In-app conversation between the requester and donor of one request.
CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(128) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL REFERENCES emergency_requests(id) ON DELETE CASCADE,
    donor_id VARCHAR(128) NOT NULL REFERENCES donors(id) ON DELETE CASCADE,
    requester_id VARCHAR(128) NOT NULL,
    last_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, donor_id)
);

CREATE INDEX IF NOT EXISTS ix_conversations_requester ON conversations (requester_id);
CREATE INDEX IF NOT EXISTS ix_conversations_donor ON conversations (donor_id);

-- Server-side messages. Access is limited to the two conversation participants.
CREATE TABLE IF NOT EXISTS messages (
    id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_messages_conversation_created
    ON messages (conversation_id, created_at);

-- Explicit, audited contact-detail shares (phone or email), one field at a time.
CREATE TABLE IF NOT EXISTS contact_shares (
    id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    request_id VARCHAR(128) NOT NULL,
    donor_id VARCHAR(128) NOT NULL,
    requester_id VARCHAR(128) NOT NULL,
    shared_by VARCHAR(128) NOT NULL,
    field VARCHAR(16) NOT NULL CHECK (field IN ('phone', 'email')),
    value VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_contact_shares_conversation
    ON contact_shares (conversation_id, created_at);
