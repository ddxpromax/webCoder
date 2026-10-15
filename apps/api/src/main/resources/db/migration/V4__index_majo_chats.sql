CREATE INDEX idx_chats_agent_archived_updated
    ON chats (agent_id, archived_at, updated_at DESC, id DESC);

CREATE INDEX idx_chats_session_updated
    ON chats (session_id, updated_at DESC, id DESC);

CREATE INDEX idx_chats_archived_updated
    ON chats (archived_at, updated_at DESC, id DESC);
