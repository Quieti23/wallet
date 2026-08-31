CREATE TABLE chains (
    chain_ref VARCHAR(191) PRIMARY KEY,
    family VARCHAR(32) NOT NULL,
    network VARCHAR(128) NOT NULL,
    finality_policy JSON NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE watch_targets (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    chain_ref VARCHAR(191) NOT NULL,
    normalized_target VARCHAR(512) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    account_id VARCHAR(128) NOT NULL,
    target_version BIGINT UNSIGNED NOT NULL,
    effective_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active TINYINT(1) NOT NULL DEFAULT 1,
    UNIQUE KEY uk_watch_target_version (chain_ref, normalized_target, target_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE scan_cursors (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    chain_ref VARCHAR(191) NOT NULL,
    scanner_id VARCHAR(64) NOT NULL,
    position_scope VARCHAR(128) NULL,
    position VARCHAR(191) NULL,
    unit_hash VARCHAR(255) NULL,
    parents_json JSON NOT NULL,
    unit_time DATETIME(6) NULL,
    status ENUM('running','paused','error') NOT NULL DEFAULT 'running',
    error_message TEXT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    UNIQUE KEY uk_scan_cursor (chain_ref, scanner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE canonical_units (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    chain_ref VARCHAR(191) NOT NULL,
    position_scope VARCHAR(128) NOT NULL DEFAULT '',
    position VARCHAR(191) NOT NULL,
    unit_hash VARCHAR(255) NOT NULL,
    parents_json JSON NOT NULL,
    unit_time DATETIME(6) NULL,
    canonical TINYINT(1) NOT NULL DEFAULT 1,
    canonical_marker TINYINT GENERATED ALWAYS AS (IF(canonical=1, 1, NULL)) STORED,
    committed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reorged_at DATETIME(6) NULL,
    UNIQUE KEY uk_canonical_unit_hash (chain_ref, position_scope, unit_hash),
    UNIQUE KEY uk_canonical_position (chain_ref, position_scope, position, canonical_marker)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE assets (
    asset_ref VARCHAR(512) PRIMARY KEY,
    chain_ref VARCHAR(191) NOT NULL,
    standard VARCHAR(32) NOT NULL,
    locator VARCHAR(255) NOT NULL,
    decimals INT UNSIGNED NOT NULL,
    symbol VARCHAR(32) NOT NULL DEFAULT '',
    UNIQUE KEY uk_assets_locator (chain_ref, standard, locator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deposit_observations (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    chain_ref VARCHAR(191) NOT NULL,
    transaction_kind VARCHAR(32) NOT NULL,
    transaction_ref VARCHAR(255) NOT NULL,
    source VARCHAR(128) NOT NULL,
    asset_ref VARCHAR(512) NOT NULL,
    from_target VARCHAR(512) NOT NULL DEFAULT '',
    to_target VARCHAR(512) NOT NULL,
    amount_raw VARCHAR(255) NOT NULL,
    amount_decimals INT UNSIGNED NOT NULL,
    inclusion_scope VARCHAR(128) NOT NULL DEFAULT '',
    inclusion_position VARCHAR(191) NOT NULL,
    inclusion_hash VARCHAR(255) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    account_id VARCHAR(128) NOT NULL,
    metadata JSON NOT NULL,
    state ENUM('OBSERVED','CONFIRMING','FINALITY_READY','CREDITED','REORGED','REVERSAL_PENDING','REVERSED') NOT NULL,
    finality_updated_at DATETIME(6) NULL,
    credited_at DATETIME(6) NULL,
    reorged_at DATETIME(6) NULL,
    UNIQUE KEY uk_deposit_identity (chain_ref, transaction_kind, transaction_ref, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deposit_state_history (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    observation_id BIGINT UNSIGNED NOT NULL,
    from_state ENUM('OBSERVED','CONFIRMING','FINALITY_READY','CREDITED','REORGED','REVERSAL_PENDING','REVERSED') NULL,
    to_state ENUM('OBSERVED','CONFIRMING','FINALITY_READY','CREDITED','REORGED','REVERSAL_PENDING','REVERSED') NOT NULL,
    reason VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ledger_entries (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    ledger_tx_id VARCHAR(256) NOT NULL,
    account_id VARCHAR(128) NOT NULL,
    asset_ref VARCHAR(512) NOT NULL,
    amount_raw VARCHAR(255) NOT NULL,
    direction ENUM('debit','credit') NOT NULL,
    entry_type ENUM('deposit_credit','deposit_reversal','deposit_restore') NOT NULL,
    reference_type VARCHAR(64) NOT NULL,
    reference_id VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    UNIQUE KEY uk_ledger_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_events (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    status ENUM('pending','published','failed') NOT NULL DEFAULT 'pending',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_outbox_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;