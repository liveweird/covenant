-- Contracts: the logical identity of an integration contract inside a System — its type (which
-- validator runs), its owner (a team XOR a user) and a name unique within the system. The
-- documents themselves are the VERSIONS (V10).
CREATE TABLE contracts (
    id SERIAL PRIMARY KEY,
    system_id INTEGER NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    -- A CHECK on purpose (the V1 role idiom): the type drives which validator runs and which
    -- checker engines are called; widening it is a deliberate migration.
    "type" VARCHAR(20) NOT NULL CHECK ("type" IN ('OPENAPI', 'ASYNCAPI', 'ODCS')),
    "name" VARCHAR(100) NOT NULL,
    description VARCHAR(2000),
    -- Ownership: exactly one of the two (the writer guard reads both).
    owner_team_id INTEGER REFERENCES teams(id) ON DELETE RESTRICT,
    owner_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_contracts_owner_xor CHECK ((owner_team_id IS NULL) <> (owner_user_id IS NULL)),
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- A contract name is unique WITHIN its system among active rows, case-insensitively.
CREATE UNIQUE INDEX uq_contracts_system_name_active ON contracts (system_id, LOWER("name")) WHERE NOT marked_as_deleted;
CREATE INDEX idx_contracts_system_id ON contracts(system_id);
CREATE INDEX idx_contracts_owner_team_id ON contracts(owner_team_id);
CREATE INDEX idx_contracts_owner_user_id ON contracts(owner_user_id);
