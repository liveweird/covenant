-- Contract versions: one row = one SemVer version of a contract = one standard document
-- (OpenAPI / AsyncAPI / ODCS) stored VERBATIM. Everything Covenant reads out of the document
-- (title, spec version) or computes about it (findings) sits in columns beside the text.
CREATE TABLE contract_versions (
    id SERIAL PRIMARY KEY,
    contract_id INTEGER NOT NULL REFERENCES contracts(id) ON DELETE RESTRICT,
    -- The SemVer string as typed (unique per contract) plus its parsed parts for SQL ordering;
    -- full SemVer 2.0 precedence (dot-separated prerelease identifiers) is the Kotlin comparator.
    "version" VARCHAR(64) NOT NULL,
    semver_major INTEGER NOT NULL,
    semver_minor INTEGER NOT NULL,
    semver_patch INTEGER NOT NULL,
    semver_prerelease VARCHAR(100),                    -- NULL = a release (sorts above prereleases)
    -- The lifecycle state machine (contracts/Lifecycle.kt) — a CHECK because the value drives
    -- behavior (content lock from ACTIVE on, delete only in DRAFT).
    lifecycle VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
        CHECK (lifecycle IN ('DRAFT', 'PROPOSED', 'ACTIVE', 'DEPRECATED', 'RETIRED')),
    format VARCHAR(4) NOT NULL CHECK (format IN ('yaml', 'json')),
    content TEXT NOT NULL,                             -- byte-exact, never rewritten
    content_sha256 CHAR(64) NOT NULL,                  -- cheap "changed?" and the sync baseline (M2)
    -- Metadata extracted at write time (display only; the document stays the source of truth).
    doc_title VARCHAR(200),
    doc_description VARCHAR(2000),
    spec_version VARCHAR(20),
    -- The check snapshot of THIS content: a JSON array of findings replaced wholesale with the
    -- text (the wholesale-replace JSON-in-TEXT idiom), plus the denormalized counts the list
    -- badges and filters read. check_complete = FALSE when the checker sidecar was unavailable.
    findings TEXT NOT NULL DEFAULT '[]',
    check_errors INTEGER NOT NULL DEFAULT 0,
    check_warnings INTEGER NOT NULL DEFAULT 0,
    check_infos INTEGER NOT NULL DEFAULT 0,
    check_complete BOOLEAN NOT NULL DEFAULT TRUE,
    checked_at BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_contract_versions_version_active ON contract_versions (contract_id, "version") WHERE NOT marked_as_deleted;
CREATE INDEX idx_contract_versions_contract_id ON contract_versions(contract_id);

-- The denormalized pointer to the highest-SemVer ACTIVE-ROW version, recomputed in the same
-- transaction as every version insert/soft-delete (ContractVersionService.recomputeLatest), so
-- the contracts list's lifecycle filter and "latest" badge are plain joins.
ALTER TABLE contracts ADD COLUMN latest_version_id INTEGER REFERENCES contract_versions(id) ON DELETE SET NULL;
