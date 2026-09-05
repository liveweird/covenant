-- Systems: the application/component exposing contracts, each inside exactly one Domain.
-- ON DELETE RESTRICT on purpose — a domain is soft-deleted anyway (never hard-removed), and
-- the service refuses to delete a domain that still holds an active system (409).
CREATE TABLE systems (
    id SERIAL PRIMARY KEY,
    domain_id INTEGER NOT NULL REFERENCES domains(id) ON DELETE RESTRICT,
    "name" VARCHAR(100) NOT NULL,
    description VARCHAR(2000),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- A system name is unique WITHIN its domain among active rows (two domains may each have an
-- "api-gateway"); case-insensitive like every registry name.
CREATE UNIQUE INDEX uq_systems_domain_name_active ON systems (domain_id, LOWER("name")) WHERE NOT marked_as_deleted;
CREATE INDEX idx_systems_domain_id ON systems(domain_id);
