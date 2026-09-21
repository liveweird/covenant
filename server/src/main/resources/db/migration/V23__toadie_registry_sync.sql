-- Optional one-way Domain/System/Team metadata synchronization from a verified Toadie snapshot.
ALTER TABLE toadie_connections
    ADD COLUMN registry_domain_blueprint VARCHAR(100),
    ADD COLUMN registry_system_domain_relation VARCHAR(100),
    ADD COLUMN registry_domain_parent_relation VARCHAR(100),
    ADD COLUMN registry_flatten_domains BOOLEAN,
    ADD COLUMN registry_domain_description_property VARCHAR(100),
    ADD COLUMN registry_system_description_property VARCHAR(100),
    ADD COLUMN registry_team_description_property VARCHAR(100);

ALTER TABLE toadie_snapshot_entities
    ADD COLUMN registry_description VARCHAR(2000),
    ADD COLUMN registry_error_code VARCHAR(100);

CREATE TABLE domain_toadie_sources (
    domain_id INTEGER PRIMARY KEY REFERENCES domains(id) ON DELETE CASCADE,
    connection_id INTEGER NOT NULL REFERENCES toadie_connections(id) ON DELETE RESTRICT,
    entity_id VARCHAR(100) NOT NULL,
    identifier VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2048),
    missing BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_code VARCHAR(100),
    last_synced_at BIGINT,
    description_synced BOOLEAN NOT NULL,
    UNIQUE (connection_id, entity_id)
);

CREATE TABLE system_toadie_sources (
    system_id INTEGER PRIMARY KEY REFERENCES systems(id) ON DELETE CASCADE,
    connection_id INTEGER NOT NULL REFERENCES toadie_connections(id) ON DELETE RESTRICT,
    entity_id VARCHAR(100) NOT NULL,
    identifier VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2048),
    missing BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_code VARCHAR(100),
    last_synced_at BIGINT,
    description_synced BOOLEAN NOT NULL,
    fallback_domain_id INTEGER REFERENCES domains(id) ON DELETE RESTRICT,
    UNIQUE (connection_id, entity_id)
);

CREATE TABLE team_toadie_sources (
    team_id INTEGER PRIMARY KEY REFERENCES teams(id) ON DELETE CASCADE,
    connection_id INTEGER NOT NULL REFERENCES toadie_connections(id) ON DELETE RESTRICT,
    entity_id VARCHAR(100) NOT NULL,
    identifier VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2048),
    missing BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_code VARCHAR(100),
    last_synced_at BIGINT,
    description_synced BOOLEAN NOT NULL,
    UNIQUE (connection_id, entity_id)
);
