-- The contract's structural change history — Lettuce's EventLogTable shape (infra/db/EventLog.kt),
-- the first clone in Covenant: an FK to the owning contract, the acting user, a timestamp, a
-- type from the Kotlin enum (no CHECK — the enum is the whitelist) and a JSON params map the
-- SPA localizes. Never document text: a content change is recorded as the FACT of the change
-- (plus the version), the diff is computed live from the stored versions. A hard-delete table
-- whose CASCADE is vestigial — contracts soft-delete, so events outlive their contract.
CREATE TABLE contract_events (
    id SERIAL PRIMARY KEY,
    contract_id INTEGER NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    user_id     BIGINT  NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at  BIGINT  NOT NULL,
    event_type  VARCHAR(40) NOT NULL,
    params      TEXT NOT NULL
);
CREATE INDEX idx_contract_events_contract_id ON contract_events(contract_id);
