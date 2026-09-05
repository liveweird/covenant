-- Environments (milestone 3c): ADMIN-curated connection targets of a System (dev/staging/prod…)
-- that the try-it feature reaches on a user's behalf — an HTTP base URL for OpenAPI, a Kafka
-- connection for AsyncAPI and/or a read-only PostgreSQL connection for ODCS. The registry IS the
-- SSRF control (only ADMIN says where the server connects), so targets may be internal hosts.
-- Passwords are ciphertext (infra/crypto/FieldCipher `enc:v1:` envelopes) — never filter or
-- sort on them in SQL. Non-secret fields (URLs, bootstrap servers, usernames) stay plaintext:
-- every authenticated user reads them.
CREATE TABLE environments (
    id SERIAL PRIMARY KEY,
    system_id INTEGER NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    "name" VARCHAR(50) NOT NULL,
    description VARCHAR(2000),
    http_base_url VARCHAR(2048),
    kafka_bootstrap_servers VARCHAR(1024),
    kafka_security_protocol VARCHAR(20)
        CHECK (kafka_security_protocol IN ('PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL')),
    kafka_sasl_mechanism VARCHAR(20)
        CHECK (kafka_sasl_mechanism IN ('PLAIN', 'SCRAM_SHA_256', 'SCRAM_SHA_512')),
    kafka_username VARCHAR(200),
    kafka_password TEXT,            -- encrypted at rest
    pg_jdbc_url VARCHAR(2048),
    pg_username VARCHAR(200),
    pg_password TEXT,               -- encrypted at rest
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- An environment name is unique WITHIN its system among active rows (a deleted one frees it).
CREATE UNIQUE INDEX uq_environments_system_name_active
    ON environments (system_id, LOWER("name")) WHERE NOT marked_as_deleted;
CREATE INDEX idx_environments_system_id ON environments(system_id);
