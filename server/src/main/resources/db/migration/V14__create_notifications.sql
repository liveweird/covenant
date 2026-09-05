-- In-app notifications (Lettuce's V13 + V17 consolidated), minted as a side-effect of contract
-- events for the contract's followers (no create endpoint). Stored STRUCTURALLY — a type from
-- the Kotlin enum (no CHECK: the enum is the whitelist) plus a JSON params map of proper nouns
-- and version numbers — so the SPA renders each one in the viewer's language; the optional link
-- is a language-independent SPA path. Soft-deleted like every business row.
CREATE TABLE notifications (
    id                SERIAL      PRIMARY KEY,
    recipient_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT      NOT NULL,
    notification_type VARCHAR(60) NOT NULL,
    params            TEXT        NOT NULL,   -- JSON object of string params; "{}" when none
    link              TEXT,
    was_seen          BOOLEAN     NOT NULL DEFAULT FALSE,
    marked_as_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_notifications_recipient_id ON notifications(recipient_id);
CREATE INDEX idx_notifications_marked_as_deleted ON notifications(marked_as_deleted);
-- The bell's badge is `total` of a pageSize-1 unseen query — this partial index answers it.
CREATE INDEX idx_notifications_unseen ON notifications(recipient_id) WHERE NOT was_seen AND NOT marked_as_deleted;
