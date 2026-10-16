ALTER TABLE settings
    ADD COLUMN audio_mode VARCHAR(16) NOT NULL DEFAULT 'auto',
    ADD COLUMN transcription_provider_type VARCHAR(32) NOT NULL DEFAULT 'disabled',
    ADD COLUMN transcription_provider_id VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN heartbeat_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN heartbeat_every VARCHAR(16) NOT NULL DEFAULT '6h',
    ADD COLUMN heartbeat_target VARCHAR(64) NOT NULL DEFAULT 'main',
    ADD COLUMN heartbeat_timeout_seconds INT NOT NULL DEFAULT 120,
    ADD COLUMN heartbeat_active_hours_start VARCHAR(5) NULL,
    ADD COLUMN heartbeat_active_hours_end VARCHAR(5) NULL,
    ADD COLUMN theme_accent VARCHAR(16) NULL,
    ADD COLUMN theme_accent_dark VARCHAR(16) NULL,
    ADD COLUMN user_timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
