CREATE TABLE IF NOT EXISTS identities (
    id VARCHAR(36) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS accounts (
    identity_id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES identities(id)
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES identities(id)
);
CREATE TABLE IF NOT EXISTS templates (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(80) NOT NULL,
    cover_url VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    required_image_count INT NOT NULL,
    accepted_mime_types VARCHAR(255) NOT NULL,
    max_file_size_bytes BIGINT NOT NULL,
    tag VARCHAR(40),
    sort_order INT NOT NULL
);
CREATE TABLE IF NOT EXISTS assets (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(40) NOT NULL,
    size_bytes BIGINT NOT NULL,
    file_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES identities(id),
    FOREIGN KEY (template_id) REFERENCES templates(id)
);
CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    template_title VARCHAR(120) NOT NULL,
    template_cover_url VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_stage VARCHAR(16),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    result_file_name VARCHAR(80),
    result_mime_type VARCHAR(40),
    failure_reason VARCHAR(255),
    fail_stage VARCHAR(16),
    step_delay_ms INT NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES identities(id),
    FOREIGN KEY (template_id) REFERENCES templates(id),
    INDEX tasks_owner_created (owner_id, created_at)
);
CREATE TABLE IF NOT EXISTS task_assets (
    task_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (task_id, position_index),
    UNIQUE (task_id, asset_id),
    FOREIGN KEY (task_id) REFERENCES tasks(id),
    FOREIGN KEY (asset_id) REFERENCES assets(id)
);
CREATE TABLE IF NOT EXISTS task_events (
    task_id VARCHAR(36) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    stage_order INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    PRIMARY KEY (task_id, stage),
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);
