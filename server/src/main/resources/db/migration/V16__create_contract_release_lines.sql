-- One policy row per contract major version. Rows are retained when their last active
-- version is deleted, so support policy is not lost when a draft line is temporarily empty.
CREATE TABLE contract_release_lines (
    id SERIAL PRIMARY KEY,
    contract_id INTEGER NOT NULL REFERENCES contracts(id) ON DELETE RESTRICT,
    major INTEGER NOT NULL CHECK (major >= 0),
    support_status VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED'
        CHECK (support_status IN ('UNSPECIFIED', 'SUPPORTED', 'MAINTENANCE', 'END_OF_LIFE')),
    support_ends_on VARCHAR(10),
    support_policy VARCHAR(2000),
    recommended_version_id INTEGER REFERENCES contract_versions(id) ON DELETE SET NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_contract_release_lines_contract_major UNIQUE (contract_id, major)
);

CREATE INDEX idx_contract_release_lines_contract_id ON contract_release_lines(contract_id);
CREATE INDEX idx_contract_release_lines_marked_as_deleted ON contract_release_lines(marked_as_deleted);

-- Existing catalogs gain one neutral line per major represented by a nondeleted version.
INSERT INTO contract_release_lines (contract_id, major, updated_at)
SELECT v.contract_id, v.semver_major, MAX(v.updated_at)
FROM contract_versions v
JOIN contracts c ON c.id = v.contract_id AND NOT c.marked_as_deleted
WHERE NOT v.marked_as_deleted
GROUP BY v.contract_id, v.semver_major;

-- SemVer precedence ignores build metadata. Replace the verbatim-string identity with the
-- precedence tuple while retaining prerelease text (empty means a stable release).
DROP INDEX uq_contract_versions_version_active;
CREATE UNIQUE INDEX uq_contract_versions_precedence_active
    ON contract_versions (contract_id, semver_major, semver_minor, semver_patch, COALESCE(semver_prerelease, ''))
    WHERE NOT marked_as_deleted;
