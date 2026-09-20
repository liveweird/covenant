-- ADMIN-curated Toadie connections, complete cached graph snapshots, and explicit contract/API links.
CREATE TABLE toadie_connections (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    browser_url VARCHAR(2048) NOT NULL,
    api_key TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    refresh_interval_minutes INTEGER NOT NULL CHECK (refresh_interval_minutes BETWEEN 1 AND 10080),
    service_blueprint VARCHAR(100) NOT NULL,
    api_blueprint VARCHAR(100) NOT NULL,
    provides_relation VARCHAR(100) NOT NULL,
    consumes_relation VARCHAR(100) NOT NULL,
    system_relation VARCHAR(100) NOT NULL,
    snapshot_system_blueprint VARCHAR(100),
    config_revision BIGINT NOT NULL DEFAULT 1,
    last_attempt_at BIGINT,
    last_success_at BIGINT,
    last_error_code VARCHAR(100),
    refreshing BOOLEAN NOT NULL DEFAULT FALSE,
    lease_until BIGINT,
    refresh_token VARCHAR(36),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_toadie_connections_name_active
    ON toadie_connections (LOWER(name)) WHERE NOT marked_as_deleted;

CREATE TABLE toadie_snapshot_entities (
    connection_id INTEGER NOT NULL REFERENCES toadie_connections(id) ON DELETE CASCADE,
    entity_id VARCHAR(100) NOT NULL,
    blueprint VARCHAR(100) NOT NULL,
    identifier VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    team_identifiers TEXT NOT NULL,
    relations TEXT NOT NULL,
    remote_updated_at BIGINT NOT NULL,
    PRIMARY KEY (connection_id, entity_id)
);
CREATE INDEX idx_toadie_snapshot_entities_kind
    ON toadie_snapshot_entities(connection_id, blueprint);

CREATE TABLE contract_toadie_links (
    id SERIAL PRIMARY KEY,
    contract_id INTEGER NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    connection_id INTEGER NOT NULL REFERENCES toadie_connections(id) ON DELETE RESTRICT,
    api_entity_id VARCHAR(100) NOT NULL,
    identifier VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2048),
    created_at BIGINT NOT NULL,
    UNIQUE (contract_id, api_entity_id)
);
CREATE INDEX idx_contract_toadie_links_contract ON contract_toadie_links(contract_id);
