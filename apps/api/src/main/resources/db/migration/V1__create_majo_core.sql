CREATE TABLE settings (
    id INT NOT NULL PRIMARY KEY,
    api_key VARCHAR(512) NOT NULL DEFAULT '',
    base_url VARCHAR(512) NOT NULL DEFAULT 'https://api.openai.com/v1',
    model_name VARCHAR(256) NOT NULL DEFAULT 'gpt-4o-mini',
    workspace VARCHAR(1024) NOT NULL DEFAULT '',
    CONSTRAINT chk_settings_single_row CHECK (id = 1)
) ENGINE = InnoDB;

INSERT INTO settings (id) VALUES (1);

CREATE TABLE chats (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    status VARCHAR(10) NOT NULL DEFAULT 'idle',
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    user_id VARCHAR(64) NOT NULL DEFAULT 'default',
    channel VARCHAR(64) NOT NULL DEFAULT 'console',
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at DATETIME(6) NULL,
    agent_id VARCHAR(64) NULL DEFAULT 'default',
    project_dir VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB;

CREATE TABLE messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chat_id VARCHAR(36) NOT NULL,
    role VARCHAR(10) NOT NULL,
    content LONGTEXT NOT NULL,
    tool_calls LONGTEXT NULL,
    thinking LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_messages_chat
        FOREIGN KEY (chat_id) REFERENCES chats (id) ON DELETE CASCADE,
    INDEX idx_messages_chat_id (chat_id),
    INDEX idx_messages_created (chat_id, created_at)
) ENGINE = InnoDB;