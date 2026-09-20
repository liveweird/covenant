ALTER TABLE contract_versions
    ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1 CHECK (content_revision >= 1);

CREATE TABLE version_reviews (
    id SERIAL PRIMARY KEY,
    version_id INTEGER NOT NULL REFERENCES contract_versions(id) ON DELETE RESTRICT,
    content_revision BIGINT NOT NULL CHECK (content_revision >= 1),
    content_sha256 CHAR(64) NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_at BIGINT NOT NULL,
    closed_at BIGINT,
    close_reason VARCHAR(32),
    CONSTRAINT ck_version_reviews_close
        CHECK ((closed_at IS NULL AND close_reason IS NULL) OR
               (closed_at IS NOT NULL AND close_reason IN ('CONTENT_CHANGED', 'WITHDRAWN', 'PUBLISHED')))
);

CREATE UNIQUE INDEX uq_version_reviews_open_version
    ON version_reviews(version_id) WHERE closed_at IS NULL;
CREATE INDEX idx_version_reviews_version_requested
    ON version_reviews(version_id, requested_at DESC, id DESC);

CREATE TABLE version_review_entries (
    id SERIAL PRIMARY KEY,
    review_id INTEGER NOT NULL REFERENCES version_reviews(id) ON DELETE RESTRICT,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('COMMENT', 'APPROVED', 'CHANGES_REQUESTED')),
    body VARCHAR(4000),
    author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    CONSTRAINT ck_version_review_entries_body
        CHECK ((kind = 'APPROVED') OR (body IS NOT NULL AND LENGTH(BTRIM(body)) > 0))
);

CREATE INDEX idx_version_review_entries_review_id
    ON version_review_entries(review_id, id);
CREATE INDEX idx_version_review_entries_review_author_id
    ON version_review_entries(review_id, author_id, id);
