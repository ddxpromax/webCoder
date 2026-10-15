CREATE TABLE token_usage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    usage_date VARCHAR(10) NOT NULL,
    provider_id VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    call_count INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_token_usage_date_provider_model
        (usage_date, provider_id, model)
) ENGINE = InnoDB;

CREATE TABLE token_usage_turns (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL DEFAULT 'default',
    chat_id VARCHAR(36) NULL,
    provider_id VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_token_usage_turns_agent_time
        (agent_id, created_at),
    INDEX idx_token_usage_turns_chat_time
        (chat_id, created_at)
) ENGINE = InnoDB;
