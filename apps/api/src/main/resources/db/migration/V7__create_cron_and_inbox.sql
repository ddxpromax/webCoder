CREATE TABLE inbox_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL DEFAULT 'default',
    source_type VARCHAR(64) NOT NULL DEFAULT '',
    source_id VARCHAR(255) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(64) NOT NULL DEFAULT '',
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    title VARCHAR(512) NOT NULL DEFAULT '',
    body LONGTEXT NOT NULL,
    payload JSON NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_inbox_events_created (created_at DESC, id DESC),
    INDEX idx_inbox_events_agent_created (agent_id, created_at DESC, id DESC),
    INDEX idx_inbox_events_unread (is_read, created_at DESC, id DESC),
    INDEX idx_inbox_events_filters (source_type, status, created_at DESC, id DESC)
) ENGINE = InnoDB;

CREATE TABLE inbox_traces (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL DEFAULT 'default',
    status VARCHAR(32) NOT NULL DEFAULT 'running',
    meta JSON NOT NULL,
    events JSON NOT NULL,
    error LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_inbox_traces_agent_created (agent_id, created_at DESC)
) ENGINE = InnoDB;

CREATE TABLE cron_jobs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL DEFAULT 'default',
    spec JSON NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    running_count INT NOT NULL DEFAULT 0,
    last_started_at DATETIME(6) NULL,
    last_finished_at DATETIME(6) NULL,
    last_status VARCHAR(32) NULL,
    last_error LONGTEXT NULL,
    next_run_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_cron_jobs_due (agent_id, paused, next_run_at),
    INDEX idx_cron_jobs_updated (agent_id, updated_at DESC)
) ENGINE = InnoDB;

CREATE TABLE cron_job_runs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(64) NOT NULL DEFAULT 'default',
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    error LONGTEXT NULL,
    result JSON NULL,
    INDEX idx_cron_job_runs_history (job_id, started_at DESC, id DESC),
    CONSTRAINT fk_cron_job_runs_job FOREIGN KEY (job_id)
        REFERENCES cron_jobs (id) ON DELETE CASCADE
) ENGINE = InnoDB;
