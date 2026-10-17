CREATE TABLE plugin_cache (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL DEFAULT '',
    description LONGTEXT NULL,
    version VARCHAR(32) NOT NULL DEFAULT '',
    author VARCHAR(255) NULL,
    kind VARCHAR(32) NULL,
    size_bytes BIGINT NULL,
    display_size VARCHAR(32) NULL,
    install_url VARCHAR(512) NULL,
    sha256 VARCHAR(128) NULL,
    category VARCHAR(64) NULL,
    display_name VARCHAR(255) NULL,
    developer VARCHAR(255) NULL,
    owner VARCHAR(128) NULL,
    logo_url VARCHAR(512) NULL,
    downloads BIGINT NULL,
    view_count BIGINT NULL,
    details_url VARCHAR(512) NULL,
    is_featured BOOLEAN NULL,
    compat_labels LONGTEXT NULL,
    locales LONGTEXT NULL,
    sort_rank INT NULL,
    cached_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_plugin_cache_source_name (source, name),
    INDEX idx_plugin_cache_market_category (source, category, name),
    INDEX idx_plugin_cache_market_rank (source, sort_rank)
) ENGINE = InnoDB;

CREATE TABLE plugin_sync_configs (
    config_key VARCHAR(64) NOT NULL PRIMARY KEY,
    url VARCHAR(512) NOT NULL,
    last_synced_at DATETIME(6) NULL,
    synced_count INT NOT NULL DEFAULT 0,
    sync_status VARCHAR(16) NOT NULL DEFAULT 'pending'
) ENGINE = InnoDB;
