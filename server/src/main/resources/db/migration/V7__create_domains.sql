-- Domains: the top level of the Domain → System → Contract hierarchy — an ADMIN-curated
-- registry (e.g. "payments", "identity") every System belongs to.
CREATE TABLE domains (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(100) NOT NULL,
    description VARCHAR(2000),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Name uniqueness among ACTIVE domains only, case-insensitively (the V6 teams shape).
CREATE UNIQUE INDEX uq_domains_name_active ON domains (LOWER("name")) WHERE NOT marked_as_deleted;
