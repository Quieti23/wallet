CREATE TABLE scan_partition (
    partition_id VARCHAR(100) PRIMARY KEY,
    chain VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128),
    lease_version BIGINT NOT NULL,
    lease_until TIMESTAMP(6) NOT NULL,
    checkpoint_height BIGINT NOT NULL,
    checkpoint_hash VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE chain_block (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain VARCHAR(32) NOT NULL,
    height BIGINT NOT NULL,
    block_hash VARCHAR(128) NOT NULL,
    parent_hash VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_chain_block_height UNIQUE (chain, height),
    CONSTRAINT uk_chain_block_hash UNIQUE (chain, block_hash)
);