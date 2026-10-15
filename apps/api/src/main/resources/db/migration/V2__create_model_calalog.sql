CREATE TABLE model_configs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    api_key VARCHAR(512) NOT NULL,
    base_url VARCHAR(512) NOT NULL DEFAULT 'https://api.openai.com/v1',
    model_name VARCHAR(256) NOT NULL DEFAULT 'gpt-4o-mini',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB;

CREATE TABLE providers (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(512) NOT NULL DEFAULT '',
    api_key VARCHAR(512) NOT NULL DEFAULT '',
    chat_model VARCHAR(64) NOT NULL DEFAULT 'OpenAIChatModel',
    api_key_prefix VARCHAR(64) NOT NULL DEFAULT '',
    api_key_prefixes LONGTEXT NULL,
    is_local BOOLEAN NOT NULL DEFAULT FALSE,
    freeze_url BOOLEAN NOT NULL DEFAULT FALSE,
    require_api_key BOOLEAN NOT NULL DEFAULT TRUE,
    is_custom BOOLEAN NOT NULL DEFAULT FALSE,
    support_model_discovery BOOLEAN NOT NULL DEFAULT FALSE,
    support_connection_check BOOLEAN NOT NULL DEFAULT TRUE,
    auth_mode VARCHAR(32) NOT NULL DEFAULT 'api_key',
    supports_oauth BOOLEAN NOT NULL DEFAULT FALSE,
    oauth_connected BOOLEAN NOT NULL DEFAULT FALSE,
    is_free_tier BOOLEAN NOT NULL DEFAULT FALSE,
    provider_group VARCHAR(64) NOT NULL DEFAULT '',
    provider_group_name VARCHAR(128) NOT NULL DEFAULT '',
    provider_variant VARCHAR(64) NOT NULL DEFAULT '',
    thinking_param_style VARCHAR(16) NULL,
    reasoning_effort_options LONGTEXT NULL,
    thinking_budget_range LONGTEXT NULL,
    generate_kwargs LONGTEXT NULL,
    custom_headers LONGTEXT NULL,
    extra_fields LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB;

CREATE TABLE provider_models (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_id VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    supports_multimodal BOOLEAN NOT NULL DEFAULT FALSE,
    supports_image BOOLEAN NOT NULL DEFAULT FALSE,
    supports_video BOOLEAN NOT NULL DEFAULT FALSE,
    probe_source VARCHAR(64) NULL,
    is_free BOOLEAN NOT NULL DEFAULT FALSE,
    max_tokens INT NOT NULL DEFAULT 8192,
    max_input_length INT NOT NULL DEFAULT 131072,
    max_input_length_configured BOOLEAN NOT NULL DEFAULT FALSE,
    relay_reasoning BOOLEAN NOT NULL DEFAULT TRUE,
    thinking_enabled BOOLEAN NULL,
    thinking_budget INT NULL,
    reasoning_effort VARCHAR(32) NULL,
    thinking_param_style VARCHAR(16) NULL,
    generate_kwargs LONGTEXT NULL,
    reasoning_effort_options LONGTEXT NULL,
    thinking_budget_range LONGTEXT NULL,
    extra_fields LONGTEXT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,

    INDEX idx_models_provider (provider_id),
    CONSTRAINT fk_models_provider
        FOREIGN KEY (provider_id)
        REFERENCES providers (id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE active_models (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(16) NOT NULL DEFAULT 'global',
    agent_id VARCHAR(64) NULL,
    provider_id VARCHAR(64) NOT NULL DEFAULT '',
    model_id VARCHAR(128) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_active_models_scope_agent
        UNIQUE (scope, agent_id)
) ENGINE = InnoDB;

INSERT INTO active_models (
    scope,
    agent_id,
    provider_id,
    model_id
)
SELECT 'global', NULL, '', ''
WHERE NOT EXISTS (
    SELECT 1
    FROM active_models
    WHERE scope = 'global'
      AND agent_id IS NULL
);